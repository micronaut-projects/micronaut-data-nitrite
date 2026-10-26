/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.data.nitrite.runtime;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.data.annotation.Index;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.exceptions.DataAccessException;
import io.micronaut.data.model.Embedded;
import io.micronaut.data.model.runtime.RuntimeAssociation;
import io.micronaut.data.model.runtime.RuntimePersistentEntity;
import io.micronaut.data.model.runtime.RuntimePersistentProperty;
import io.micronaut.data.nitrite.annotation.FullTextIndex;
import io.micronaut.data.nitrite.annotation.SpatialIndex;
import io.micronaut.data.nitrite.conf.NitriteConfiguration;
import io.micronaut.data.nitrite.runtime.mapping.NitriteEntityMapper;
import io.micronaut.data.nitrite.transaction.NitriteTransactionContext;
import io.micronaut.data.nitrite.transaction.NitriteTransactionHolder;
import org.dizitart.no2.Nitrite;
import org.dizitart.no2.collection.NitriteCollection;
import org.dizitart.no2.exceptions.NitriteSecurityException;
import org.dizitart.no2.index.IndexOptions;
import org.dizitart.no2.index.IndexType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.dizitart.no2.index.IndexOptions.indexOptions;

/**
 * Manages Nitrite collection lookup and index creation.
 * Caches collections and ensures indexes are created once per collection.
 *
 */
@Internal
final class NitriteCollectionRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(NitriteCollectionRegistry.class);
    private static final String DESERIALIZATION_REJECTED = "is not allowed to be deserialized";

    private final Nitrite database;
    private final NitriteTransactionHolder transactionHolder;
    private final NitriteConfiguration configuration;
    private final Function<Class<?>, RuntimePersistentEntity<?>> entityFactory;

    private final Set<String> indexedCollections = ConcurrentHashMap.newKeySet();
    private final Map<String, NitriteCollection> collectionCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, String> collectionNameCache = new ConcurrentHashMap<>();

    NitriteCollectionRegistry(Nitrite database,
                              NitriteTransactionHolder transactionHolder,
                              NitriteConfiguration configuration,
                              Function<Class<?>, RuntimePersistentEntity<?>> entityFactory) {
        this.database = database;
        this.transactionHolder = transactionHolder;
        this.configuration = configuration;
        this.entityFactory = entityFactory;
    }

    String getCollectionName(Class<?> type) {
        return collectionNameCache.computeIfAbsent(type, t -> {
            MappedEntity mappedEntity = t.getAnnotation(MappedEntity.class);
            return mappedEntity != null && !mappedEntity.value().isEmpty() ? mappedEntity.value() : t.getSimpleName();
        });
    }

    NitriteCollection getCollection(Class<?> type) {
        String name = getCollectionName(type);
        NitriteCollection collection;
        if (transactionHolder.isActive()) {
            // Nitrite transactions require the collection to pre-exist before the transaction started.
            // Touch the collection on the database first (idempotent: creates if absent), reusing
            // collectionCache so the touch only happens once per name instead of on every call.
            collectionCache.computeIfAbsent(name, this::openCollection);
            NitriteTransactionContext tx = transactionHolder.get();
            if (tx != null) {
                collection = tx.getCollection(name);
            } else {
                collection = collectionCache.computeIfAbsent(name, this::openCollection);
            }
        } else {
            collection = collectionCache.computeIfAbsent(name, this::openCollection);
        }
        ensureIndexes(type, collection, name);
        return collection;
    }

    private NitriteCollection openCollection(String name) {
        try {
            return database.getCollection(name);
        } catch (NitriteSecurityException e) {
            throw openFailure(e, name, configuration.getName());
        }
    }

    /**
     * MVStore rejects a stored class that is not allowlisted when it opens the collection's map,
     * before any read or write runs. That rejection becomes a {@link DataAccessException} naming
     * the datasource property that allowlists the class; other security failures are unchanged.
     *
     * @param failure the failure raised while opening the collection
     * @param collection the collection name
     * @param dataSourceName the datasource name
     * @return the exception to throw
     */
    static RuntimeException openFailure(NitriteSecurityException failure, String collection, String dataSourceName) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains(DESERIALIZATION_REJECTED)) {
                return new DataAccessException("Unable to open Nitrite collection " + collection + ": "
                    + failure.getMessage() + ". For Micronaut Data, configure `micronaut.nitrite." + dataSourceName
                    + ".mvstore-allowed-classes` with a JEP 290 pattern for the trusted application class.", failure);
            }
        }
        return failure;
    }

    /**
     * Rejects a repository write before entity callbacks, cascades or collection mutations run.
     * The bound context represents the physical transaction, so a participating write method
     * cannot override an outer transaction's read-only setting.
     */
    void checkWritable() {
        NitriteTransactionContext transaction = transactionHolder.get();
        if (transaction != null && transaction.isReadOnly()) {
            throw new DataAccessException("Cannot execute a write operation in a read-only Nitrite transaction");
        }
    }

    private void ensureIndexes(Class<?> type, NitriteCollection collection, String name) {
        if (!configuration.isCreateIndexes() || indexedCollections.contains(name)) {
            return;
        }
        indexedCollections.add(name);
        RuntimePersistentEntity<?> entity = entityFactory.apply(type);
        List<AnnotationValue<Index>> indexes = entity.getAnnotationMetadata().getAnnotationValuesByType(Index.class);
        for (AnnotationValue<Index> index : indexes) {
            String[] columns = index.getRequiredValue("columns", String[].class);
            String[] translatedColumns = new String[columns.length];
            for (int i = 0; i < columns.length; i++) {
                String col = columns[i];
                RuntimePersistentProperty<?> prop = entity.getPropertyByName(col);
                translatedColumns[i] = prop != null ? prop.getPersistedName() : col;
            }
            boolean unique = index.booleanValue("unique").orElse(false);
            IndexOptions options = indexOptions(unique ? IndexType.UNIQUE : IndexType.NON_UNIQUE);
            try {
                collection.createIndex(options, translatedColumns);
            } catch (Exception e) {
                LOG.warn("Could not create index for collection {}: {}", collection.getName(), e.getMessage());
            }
        }
        for (RuntimePersistentProperty<?> property : entity.getPersistentProperties()) {
            if (property.getAnnotationMetadata().hasAnnotation(Index.class)) {
                AnnotationValue<Index> index = property.getAnnotationMetadata().getAnnotation(Index.class);
                if (index != null) {
                    boolean unique = index.booleanValue("unique").orElse(false);
                    try {
                        collection.createIndex(indexOptions(unique ? IndexType.UNIQUE : IndexType.NON_UNIQUE), property.getPersistedName());
                    } catch (Exception e) {
                        LOG.warn("Could not create index for field {} in collection {}: {}", property.getName(), collection.getName(), e.getMessage());
                    }
                }
            }
            if (property.getAnnotationMetadata().hasAnnotation(FullTextIndex.class)) {
                try {
                    collection.createIndex(indexOptions(IndexType.FULL_TEXT), property.getPersistedName());
                } catch (Exception e) {
                    LOG.warn("Could not create full-text index for field {} in collection {}: {}", property.getName(), collection.getName(), e.getMessage());
                }
            }
            if (property.getAnnotationMetadata().hasAnnotation(SpatialIndex.class)) {
                try {
                    collection.createIndex(indexOptions("Spatial"), property.getPersistedName());
                } catch (Exception e) {
                    LOG.warn("Could not create spatial index for field {} in collection {}: {}", property.getName(), collection.getName(), e.getMessage());
                }
            }
        }
        ensureIdentityIndex(entity, collection);
    }

    /**
     * Creates the unique index that stands in for Nitrite's document key on an identity that key
     * cannot hold.
     *
     * <p>A Long identity is written into "_id" as well as "id", so its lookups are already a key
     * seek and its uniqueness is already the key's. A secondary index on it is pure cost: measured
     * over the benchmark suite (3 forks, 5 iterations), one took 29.8% of transactional write
     * throughput on MVSTORE, 19.5% in memory and 26.0% of association reads. Every other scalar
     * identity keeps its value in "id" alone, where only this index makes an identity filter
     * seekable and a duplicate identity a write failure rather than a second document. That write
     * failure is what Jakarta Data requires of {@code @Insert}, which must raise
     * {@code EntityExistsException} when an insert would violate a uniqueness constraint, so
     * turning index creation off for a datasource also gives up that rejection.
     *
     * @param entity the entity whose identity is indexed
     * @param collection the collection holding that entity
     */
    private void ensureIdentityIndex(RuntimePersistentEntity<?> entity, NitriteCollection collection) {
        if (!entity.hasIdentity() || entity.hasCompositeIdentity()) {
            return;
        }
        RuntimePersistentProperty<?> identity = entity.getIdentity();
        if (identity instanceof Embedded || identity instanceof RuntimeAssociation<?>) {
            return;
        }
        Class<?> idType = identity.getType();
        if (idType == Long.class || idType == long.class) {
            return;
        }
        // The entity mapper stores the identity under the canonical field name (ID_FIELD = "id"),
        // regardless of the Java property name or @MappedProperty persisted name.
        String fieldName = NitriteEntityMapper.ID_FIELD;
        if (collection.hasIndex(fieldName)) {
            return;
        }
        try {
            collection.createIndex(indexOptions(IndexType.UNIQUE), fieldName);
        } catch (Exception e) {
            LOG.warn("Could not create identity index for field {} in collection {}: {}",
                identity.getName(), collection.getName(), e.getMessage());
        }
    }
}
