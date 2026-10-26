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
package io.micronaut.data.nitrite.runtime.write;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.event.EntityEventContext;
import io.micronaut.data.exceptions.EntityExistsException;
import io.micronaut.data.exceptions.OptimisticLockException;
import io.micronaut.data.model.runtime.RuntimePersistentEntity;
import io.micronaut.data.nitrite.runtime.NitriteOperationsHelper;
import io.micronaut.data.nitrite.runtime.mapping.NitriteEntityMapper;
import io.micronaut.data.nitrite.runtime.mapping.NitriteEntityMeta;
import io.micronaut.data.nitrite.runtime.query.NitriteFilterUtils;
import io.micronaut.data.runtime.config.DataSettings;
import io.micronaut.data.runtime.event.DefaultEntityEventContext;
import io.micronaut.data.runtime.operations.internal.SyncCascadeOperations;
import io.micronaut.data.runtime.operations.internal.SyncEntitiesOperations;
import org.dizitart.no2.collection.Document;
import org.dizitart.no2.collection.NitriteCollection;
import org.dizitart.no2.exceptions.UniqueConstraintException;
import org.dizitart.no2.filters.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/**
 * Internal entities operations for Nitrite with automatic event firing and version handling.
 * <p>
 * <b>Save All (INSERT) Operation:</b> follows Jakarta Data {@code BasicRepository.save} semantics
 * for each entity: an identity found in the store is updated, and an identity not found in the
 * store is inserted.
 * This allows {@code saveAll()} to work for mixed batches of new and existing entities without
 * using Nitrite's insert-if-absent update option.
 * <p>
 * <b>Update All (UPDATE) Operation:</b> Replaces existing documents by ID, and never inserts.
 * An entity whose document is absent, or whose version does not match, affects no rows and raises
 * {@link OptimisticLockException}, versioned or not. An entity with no ID is skipped.
 *
 * @param <T> The entity type
 * @since 5.2.0
 */
@Internal
public final class NitriteEntitiesOperations<T> extends SyncEntitiesOperations<T, RuntimeException> {

    private static final Logger LOG = LoggerFactory.getLogger(NitriteEntitiesOperations.class);

    private final NitriteOperationContext ctx;
    private final NitriteCollection collection;
    private List<T> entities;
    private final boolean insert;
    /** Whether the current execute call is writing the insert half of a mixed save batch. */
    private boolean executingInsert;
    private final NitriteEntityMapper entityMapper;
    private final ObjectRepositoryWriter<T> repositoryWriter;
    private final SyncCascadeOperations<NitriteOperationContext> cascadeOperations;
    private final NitriteOperationsHelper helper;
    /** Prior version per entity, keyed by identity. Populated before every lifecycle phase. */
    private @Nullable IdentityHashMap<T, Object> priorVersions;
    /** Documents actually removed, which can be fewer than the entities passed in. */
    private long affectedCount;

    /**
     * Creates a new NitriteEntitiesOperations.
     *
     * @param ctx the operation context
     * @param writeSupport the collaborators shared by every write operation
     * @param persistentEntity the persistent entity
     * @param entities the entities to operate on
     * @param insert true if this is an insert operation
     */
    public NitriteEntitiesOperations(
            NitriteOperationContext ctx,
            NitriteWriteSupport writeSupport,
            RuntimePersistentEntity<T> persistentEntity,
            Iterable<T> entities,
            boolean insert) {
        super(writeSupport.entityEventListener(), persistentEntity, writeSupport.conversionService());
        NitriteEntityMapper entityMapper = writeSupport.entityMapper();
        NitriteOperationsHelper helper = writeSupport.helper();
        this.ctx = ctx;
        this.cascadeOperations = writeSupport.cascadeOperations();
        this.entityMapper = entityMapper;
        this.helper = helper;
        this.collection = helper.getCollection(persistentEntity.getIntrospection().getBeanType());
        this.repositoryWriter = new ObjectRepositoryWriter<>(entityMapper, persistentEntity);
        if (entities instanceof List) {
            this.entities = (List<T>) entities;
        } else {
            this.entities = new ArrayList<>();
            for (T entity : entities) {
                this.entities.add(entity);
            }
        }
        this.insert = insert;
        this.executingInsert = insert;
    }

    @Override
    public List<T> getEntities() {
        return entities;
    }

    /**
     * The number of documents actually removed by {@link #delete()}, which is lower than the number
     * of entities passed in when an entity was vetoed, had no identity, or was already gone.
     *
     * @return the affected document count
     */
    public long getAffectedCount() {
        return affectedCount;
    }

    @Override
    public void persist() {
        if (insert) {
            entities.removeIf(ctx.persisted::contains);
        }
        if (entities.isEmpty()) {
            return;
        }
        try {
            collectAutoPopulatedPreviousValues();

            List<T> newEntities = new ArrayList<>();
            List<T> existingEntities = new ArrayList<>();
            partitionByExistence(newEntities, existingEntities);

            // Pre-phase: new entities (persist lifecycle), then existing ones (update lifecycle).
            prePersist(newEntities);
            preUpdate(existingEntities);

            // Execute each lifecycle half using its matching write operation. The collection
            // insert must not be attempted for entities that were classified as existing.
            executeHalf(newEntities, true);
            executeHalf(existingEntities, false);

            // Post-phase over the halves themselves. An immutable entity given a generated id or
            // an initial version is replaced by a new instance in the list execute() was handed,
            // so the combined view has to be built from the halves after execute() has run.
            postPersist(newEntities);
            postUpdate(existingEntities);
            List<T> combined = new ArrayList<>(newEntities.size() + existingEntities.size());
            combined.addAll(newEntities);
            combined.addAll(existingEntities);
            this.entities = combined;

        } catch (EntityExistsException | OptimisticLockException e) {
            throw e;
        } catch (Exception e) {
            failed(e, "PERSIST");
        }
    }

    /**
     * Jakarta Data save determines insert/update from whether the identity exists in the store. A
     * non-null assigned identity is not enough to select update. Explicit insert is strict and
     * therefore bypasses the existence check entirely.
     */
    private void partitionByExistence(List<T> newEntities, List<T> existingEntities) {
        if (!insert && !ctx.isSave()) {
            existingEntities.addAll(entities);
            return;
        }
        NitriteEntityMeta<T> meta = entityMapper.getOrBuildMeta(persistentEntity.getIntrospection().getBeanType());
        for (T entity : entities) {
            if (existsInStore(meta, entity)) {
                existingEntities.add(entity);
            } else {
                newEntities.add(entity);
            }
        }
    }

    private boolean existsInStore(NitriteEntityMeta<T> meta, T entity) {
        Object id = entityMapper.getEntityIdValue(meta, entity);
        if (ctx.isStrictInsert() || id == null) {
            return false;
        }
        Filter identityFilter = entityMapper.idEqualsFilter(meta, id);
        return NitriteCrudOperations.exists(collection, identityFilter);
    }

    private void prePersist(List<T> half) {
        if (half.isEmpty()) {
            return;
        }
        this.entities = half;
        if (!triggerPrePersist() && persistentEntity.cascadesPersist()) {
            cascadePre(Relation.Cascade.PERSIST);
        }
    }

    private void preUpdate(List<T> half) {
        if (half.isEmpty()) {
            return;
        }
        this.entities = half;
        if (!triggerPreUpdate() && persistentEntity.cascadesUpdate()) {
            cascadePre(Relation.Cascade.UPDATE);
        }
    }

    private void executeHalf(List<T> half, boolean asInsert) {
        if (half.isEmpty()) {
            return;
        }
        this.entities = half;
        executingInsert = asInsert;
        execute();
    }

    private void postPersist(List<T> half) {
        if (half.isEmpty()) {
            return;
        }
        this.entities = half;
        triggerPostPersist();
        if (persistentEntity.cascadesPersist()) {
            cascadePost(Relation.Cascade.PERSIST);
        }
    }

    private void postUpdate(List<T> half) {
        if (half.isEmpty()) {
            return;
        }
        this.entities = half;
        triggerPostUpdate();
        if (persistentEntity.cascadesUpdate()) {
            cascadePost(Relation.Cascade.UPDATE);
        }
    }

    /**
     * Delete all entities with optimistic locking support.
     */
    @SuppressWarnings("unchecked")
    @Override
    public void delete() {
        if (entities.isEmpty()) {
            return;
        }

        Class<T> type = persistentEntity.getIntrospection().getBeanType();
        NitriteEntityMeta<T> meta = entityMapper.getOrBuildMeta(type);

        List<Filter> filters = new ArrayList<>();
        List<T> entitiesToDelete = new ArrayList<>();

        for (T entity : entities) {
            Object idValue = entityMapper.getEntityIdValue(meta, entity);
            if (idValue == null) {
                continue;
            }

            Filter filter = entityMapper.idEqualsFilter(persistentEntity.getIntrospection().getBeanType(), idValue);

            if (meta.versionProp() != null) {
                BeanProperty<T, Object> versionProperty = meta.versionProp().getProperty();
                Object versionValue = versionProperty.get(entity);
                filter = Filter.and(filter, NitriteFilterUtils.eq(meta.versionProp().getPersistedName(), helper.toFilterValue(versionValue)));
            }
            DefaultEntityEventContext<T> event = new DefaultEntityEventContext<>(persistentEntity, entity);
            if (entityEventListener.preRemove((EntityEventContext<Object>) event)) {
                entitiesToDelete.add(event.getEntity());
                filters.add(filter);
            }
        }

        if (entitiesToDelete.isEmpty()) {
            entities.clear();
            return;
        }

        int count = 0;
        for (int i = 0; i < filters.size(); i++) {
            if (NitriteCrudOperations.remove(collection, filters.get(i)) > 0) {
                count++;
            }
        }
        affectedCount = count;

        if (count != entitiesToDelete.size()) {
            throw new OptimisticLockException("Execute update returned unexpected row count. Expected: " + entitiesToDelete.size() + " got: " + count);
        }

        for (T entity : entitiesToDelete) {
            entityEventListener.postRemove((EntityEventContext<Object>) new DefaultEntityEventContext<>(persistentEntity, entity));
        }

        entities = entitiesToDelete;
    }

    @Override
    protected void execute() throws RuntimeException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("execute: insert={}, entities count={}", executingInsert, entities.size());
        }

        Class<T> type = persistentEntity.getIntrospection().getBeanType();
        NitriteEntityMeta<T> meta = entityMapper.getOrBuildMeta(type);

        if (executingInsert) {
            executeInsert(type, meta);
        } else {
            executeUpdate(meta);
        }
    }

    /**
     * Standard save/insert operations use a strict batch insert. Existing save entities were
     * partitioned into the update lifecycle in persist(); no insert-if-absent update is used.
     */
    private void executeInsert(Class<T> type, NitriteEntityMeta<T> meta) {
        List<Document> docsToInsert = new ArrayList<>();
        for (int i = 0; i < entities.size(); i++) {
            T entity = entities.get(i);
            if (ctx.persisted.contains(entity)) {
                continue;
            }
            Document doc = toInsertDocument(i, entity, type, meta);
            if (doc != null) {
                docsToInsert.add(doc);
            }
        }
        if (!docsToInsert.isEmpty()) {
            insertBatch(docsToInsert);
        }
    }

    @Nullable
    private Document toInsertDocument(int index, T entity, Class<T> type, NitriteEntityMeta<T> meta) {
        if (entityMapper.getEntityIdValue(meta, entity) == null) {
            helper.generateIdIfNecessary(entity, type);
        }
        if (meta.versionProp() != null && repositoryWriter.needsVersionInit(entity)) {
            BeanProperty<T, Object> versionProperty = meta.versionProp().getProperty();
            entity = helper.updateEntityId(versionProperty, entity, 0L);
            entities.set(index, entity);
        }
        return repositoryWriter.toDocument(entity);
    }

    private void insertBatch(List<Document> docsToInsert) {
        if (DataSettings.QUERY_LOG.isDebugEnabled()) {
            helper.logInsert(collection.getName(), "batch of " + docsToInsert.size());
        }
        try {
            collection.insert(docsToInsert.toArray(new Document[0]));
        } catch (UniqueConstraintException e) {
            // Nitrite reports the collision without naming the document, and the batch is
            // written as one call, so the identity cannot be narrowed down here.
            throw new EntityExistsException(
                "One or more entities already exist in collection: " + collection.getName(), e);
        }
        affectedCount = docsToInsert.size();
        ctx.persisted.addAll(entities);
    }

    /**
     * updateAll() operation: replace existing documents by ID.
     */
    private void executeUpdate(NitriteEntityMeta<T> meta) {
        int expectedCount = 0;
        long updatedCount = 0;
        for (int i = 0; i < entities.size(); i++) {
            Object id = entityMapper.getEntityIdValue(meta, entities.get(i));
            if (id == null) {
                continue;
            }
            expectedCount++;
            updatedCount += updateById(i, id, meta);
        }
        affectedCount = updatedCount;
        if (updatedCount != expectedCount) {
            throw new OptimisticLockException("Execute update returned unexpected row count. Expected: " + expectedCount + " got: " + updatedCount);
        }
    }

    private long updateById(int index, Object id, NitriteEntityMeta<T> meta) {
        T entity = entities.get(index);
        Filter filter = entityMapper.idEqualsFilter(meta, id);
        if (meta.versionProp() != null) {
            Object versionValue = priorVersion(meta, entity);
            filter = Filter.and(filter, NitriteFilterUtils.eq(meta.versionProp().getPersistedName(), helper.toFilterValue(versionValue)));
            long nextVersion = (versionValue == null ? 0L : ((Number) versionValue).longValue()) + 1;
            entity = helper.updateEntityId(meta.versionProp().getProperty(), entity, nextVersion);
            entities.set(index, entity);
        }
        Document update = repositoryWriter.toDocument(entity);
        if (update == null) {
            return 0;
        }
        helper.logUpdate(collection.getName(), filter, update);
        return NitriteCrudOperations.update(collection, filter, update);
    }

    @Nullable
    private Object priorVersion(NitriteEntityMeta<T> meta, T entity) {
        Object versionValue = priorVersions != null ? priorVersions.get(entity) : null;
        return versionValue != null ? versionValue : meta.versionProp().getProperty().get(entity);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected boolean triggerPre(Function<EntityEventContext<Object>, Boolean> fn) {
        priorVersions = new IdentityHashMap<>();
        NitriteEntityMeta<T> meta = entityMapper.getOrBuildMeta(persistentEntity.getIntrospection().getBeanType());
        IdentityHashMap<T, Boolean> vetoedSet = null;
        for (int i = 0; i < entities.size(); i++) {
            T entity = entities.get(i);
            // Capture pre-version before the event listener can modify the field.
            if (meta.versionProp() != null) {
                priorVersions.put(entity, meta.versionProp().getProperty().get(entity));
            }
            DefaultEntityEventContext<T> event = new DefaultEntityEventContext<>(persistentEntity, entity);
            if (!fn.apply((EntityEventContext<Object>) event).booleanValue()) {
                priorVersions.remove(entity);
                if (vetoedSet == null) {
                    vetoedSet = new IdentityHashMap<>();
                }
                vetoedSet.put(entity, Boolean.TRUE);
                continue;
            }
            T newEntity = event.getEntity();
            if (!Objects.equals(entity, newEntity)) {
                entities.set(i, newEntity);
                // Carry the captured version to the replacement instance.
                Object v = priorVersions.remove(entity);
                if (v != null) {
                    priorVersions.put(newEntity, v);
                }
            }
        }
        if (vetoedSet != null) {
            veto(vetoedSet::containsKey);
            return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void triggerPost(Consumer<EntityEventContext<Object>> fn) {
        IntStream.range(0, entities.size()).forEach(i -> {
            T entity = entities.get(i);
            DefaultEntityEventContext<T> event = new DefaultEntityEventContext<>(persistentEntity, entity);
            fn.accept((EntityEventContext<Object>) event);
            T newEntity = event.getEntity();
            if (!Objects.equals(entity, newEntity)) {
                entities.set(i, newEntity);
            }
        });
    }

    @Override
    public void veto(Predicate<T> predicate) {
        entities.removeIf(predicate);
    }

    @Override
    protected void cascadePre(Relation.Cascade cascadeType) {
        entities.replaceAll(entity -> cascadeOperations.cascadeEntity(ctx, entity, persistentEntity, false, cascadeType));
    }

    @Override
    protected void cascadePost(Relation.Cascade cascadeType) {
        entities.replaceAll(entity -> cascadeOperations.cascadeEntity(ctx, entity, persistentEntity, true, cascadeType));
    }

    @Override
    protected void collectAutoPopulatedPreviousValues() {
        // Batch events capture each entity's previous version in triggerPre instead.
    }
}
