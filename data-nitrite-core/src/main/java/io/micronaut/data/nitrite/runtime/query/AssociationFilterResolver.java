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
package io.micronaut.data.nitrite.runtime.query;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.model.runtime.RuntimeAssociation;
import io.micronaut.data.model.runtime.RuntimePersistentEntity;
import io.micronaut.data.model.runtime.RuntimePersistentProperty;
import io.micronaut.data.nitrite.runtime.mapping.CompositeJoinColumn;
import io.micronaut.data.nitrite.runtime.mapping.NitriteEntityMapper;
import io.micronaut.data.nitrite.runtime.query.NitriteFilterBuilder.SubQueryExecutor;
import io.micronaut.data.nitrite.runtime.query.PathResolver.PathResolution;
import io.micronaut.data.runtime.config.DataSettings;
import org.dizitart.no2.collection.Document;
import org.dizitart.no2.filters.Filter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.EQ;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.OR;

@Internal
final class AssociationFilterResolver {

    private final NitriteEntityMapper entityMapper;
    private final @Nullable SubQueryExecutor subQueryExecutor;
    private final ValueResolver valueResolver;
    /** Callback into NitriteFilterBuilder for nested filter building. */
    // Follow-up: break this circular reference once the compile/build duality is resolved.
    private final FieldFilterProvider fieldFilterProvider;
    private final FieldFilterProvider operatorFiltersForPath;

    AssociationFilterResolver(
            NitriteEntityMapper entityMapper,
            @Nullable SubQueryExecutor subQueryExecutor,
            ValueResolver valueResolver,
            FieldFilterProvider fieldFilterProvider,
            FieldFilterProvider operatorFiltersForPath) {
        this.entityMapper = entityMapper;
        this.subQueryExecutor = subQueryExecutor;
        this.valueResolver = valueResolver;
        this.fieldFilterProvider = fieldFilterProvider;
        this.operatorFiltersForPath = operatorFiltersForPath;
    }

    @Nullable Filter buildAssociationFilter(
            RuntimePersistentEntity<?> entity,
            String field,
            Map<String, Object> operators,
            Object[] params,
            Map<String, Object> namedParameters) {

        if (!operators.containsKey(EQ)) {
            return null;
        }

        Object value = valueResolver.resolveValue(operators.get(EQ), params, namedParameters);
        if (value == null) {
            return null;
        }

        PathResolution resolution = PathResolver.resolve(entity, field);
        if (!resolution.isReference() || resolution.chain().isEmpty()) {
            return null;
        }

        RuntimeAssociation<?> headAssoc = resolution.chain().getFirst();
        RuntimePersistentEntity<?> associatedEntity = headAssoc.getAssociatedEntity();
        if (!associatedEntity.hasIdentity()) {
            // The sub-query below filters on a single id property, so the target must have exactly
            // one. hasIdentity() is false both when the target has no identity and when it has a
            // composite one; neither can drive that filter.
            return null;
        }
        if (subQueryExecutor == null || !needsSubQuery(value, associatedEntity.getIdentity().getType())) {
            return null;
        }

        Relation.Kind kind = headAssoc.getKind();
        if (kind == Relation.Kind.MANY_TO_ONE) {
            // Dotted MANY_TO_ONE paths (e.g. "author.name") go through buildNestedFilter.
            // Use the association's persisted FK name (e.g. "widget_id"), not the raw property
            // name ("widget"), so the final where(...).in(ids) targets the stored column.
            return field.contains(".")
                ? null
                : buildForwardLookupFilter(resolution.persistedField(), headAssoc, value, params, namedParameters);
        }

        if (isToMany(kind)) {
            // For dotted paths (e.g. "children.name"), extract target from the resolved terminal.
            String targetPropertyName = field.contains(".") && resolution.terminal() != null
                ? resolution.terminal().getName()
                : null;
            return buildReverseLookupFilter(entity, headAssoc, targetPropertyName, value, params, namedParameters);
        }

        return null;
    }

    /**
     * Decides whether an equality value on an association must be matched through a sub-query
     * against the associated entity rather than compared to the stored id directly.
     *
     * @param value the resolved equality value
     * @param idType the associated entity's identity type
     * @return {@code true} when the value cannot be an id of that type
     */
    private boolean needsSubQuery(Object value, Class<?> idType) {
        if (!(value instanceof String strValue)) {
            return false;
        }
        if (idType == UUID.class) {
            return !looksLikeId(strValue, idType);
        }
        return idType == String.class && strValue.contains(" ");
    }

    private @Nullable Filter buildReverseLookupFilter(
            RuntimePersistentEntity<?> entity,
            RuntimeAssociation<?> association, @Nullable String targetPropertyName,
            Object value, Object[] params, Map<String, Object> namedParameters) {

        if (subQueryExecutor == null) {
            return null;
        }
        String mappedBy = association.getAnnotationMetadata().stringValue(Relation.class, "mappedBy").orElse(null);
        if (mappedBy == null || targetPropertyName == null || targetPropertyName.equals(mappedBy)) {
            return null;
        }

        RuntimePersistentEntity<?> associatedEntity = association.getAssociatedEntity();
        RuntimePersistentProperty<?> targetProperty = findTargetProperty(associatedEntity, targetPropertyName);
        RuntimePersistentProperty<?> backRefProp = associatedEntity.getPropertyByName(mappedBy);
        if (targetProperty == null || backRefProp == null) {
            return null;
        }

        Map<String, Object> subFilterMap = Collections.singletonMap(
            targetProperty.getPersistedName(), Collections.singletonMap(EQ, value));
        return buildBackReferenceFilter(entity, associatedEntity, mappedBy, backRefProp, subFilterMap, params, namedParameters);
    }

    private static @Nullable RuntimePersistentProperty<?> findTargetProperty(
            RuntimePersistentEntity<?> entity, String name) {
        RuntimePersistentProperty<?> property = entity.getPropertyByName(name);
        if (property != null) {
            return property;
        }
        for (RuntimePersistentProperty<?> p : entity.getPersistentProperties()) {
            if (p.getPersistedName().equals(name)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Matches owners through the associated entity's back-reference: composite join columns
     * match whole key tuples, a single back-reference matches the owner ids it selects.
     *
     * @param entity the owning entity being queried
     * @param associatedEntity the entity holding the back-reference
     * @param mappedBy the back-reference property name
     * @param backRefProp the back-reference property
     * @param subFilterMap the filter applied to the associated entity
     * @param params positional query parameters
     * @param namedParameters named query parameters
     * @return the filter on the owning entity
     */
    private Filter buildBackReferenceFilter(
            RuntimePersistentEntity<?> entity,
            RuntimePersistentEntity<?> associatedEntity,
            String mappedBy,
            RuntimePersistentProperty<?> backRefProp,
            Map<String, Object> subFilterMap,
            Object[] params,
            Map<String, Object> namedParameters) {
        List<CompositeJoinColumn> joinColumns = entityMapper.getCompositeJoinColumns(
            associatedEntity.getIntrospection().getBeanType(), mappedBy);
        if (!joinColumns.isEmpty()) {
            return buildCompositeReverseLookupFilter(
                entity, associatedEntity, joinColumns, subFilterMap, params, namedParameters);
        }

        String backRefPersistedName = backRefProp.getPersistedName();
        if (DataSettings.QUERY_LOG.isDebugEnabled()) {
            DataSettings.QUERY_LOG.debug("Reverse lookup sub-query: entity={}, filter={}, backRef={}",
                associatedEntity.getName(), subFilterMap, backRefPersistedName);
        }

        List<Object> matchingValues = Objects.requireNonNull(subQueryExecutor).executeSubQuery(
            associatedEntity, subFilterMap, backRefPersistedName, false, params, namedParameters);
        // The identity is stored under the canonical document field, not under the mapped
        // identity name, so an entity whose id carries a mapped name is matched here too.
        return idsIn(NitriteEntityMapper.ID_FIELD, matchingValues);
    }

    private @Nullable Filter buildForwardLookupFilter(
            String field,
            RuntimeAssociation<?> association, Object value,
            Object[] params, Map<String, Object> namedParameters) {

        if (subQueryExecutor == null) {
            return null;
        }
        RuntimePersistentEntity<?> associatedEntity = association.getAssociatedEntity();
        List<Map<String, Object>> orClauses = associatedEntity.getPersistentProperties().stream()
            .filter(p -> p.getType().isInstance(value))
            .map(p -> Map.<String, Object>of(p.getPersistedName(), Map.of(EQ, value)))
            .toList();
        if (orClauses.isEmpty()) {
            return null;
        }

        Map<String, Object> subFilterMap = Map.of(OR, orClauses);
        List<Object> matchingIds = subQueryExecutor.executeSubQuery(
            associatedEntity, subFilterMap, null, false, params, namedParameters);
        return idsIn(field, matchingIds);
    }

    @Nullable Filter buildNestedFilter(
            RuntimePersistentEntity<?> entity,
            String fieldPath,
            Map<String, Object> operators,
            Object[] params,
            Map<String, Object> namedParameters) {

        int dotIdx = fieldPath.indexOf('.');
        String firstPart = fieldPath.substring(0, dotIdx);
        String remaining = fieldPath.substring(dotIdx + 1);

        // Resolve the first segment via metadata instead of name-guessing heuristics.
        PathResolution firstResolution = PathResolver.resolve(entity, firstPart);
        RuntimePersistentProperty<?> prop = firstResolution.chain().isEmpty()
            ? null
            : firstResolution.chain().getFirst();
        if (prop == null && firstResolution.terminal() != null) {
            prop = firstResolution.terminal();
        }

        String fieldName = firstResolution.persistedField();

        if (prop instanceof RuntimeAssociation<?> assoc) {
            Relation.Kind kind = assoc.getKind();
            if (isToMany(kind)) {
                return buildCollectionFilter(entity, assoc, fieldName, remaining, operators, params, namedParameters);
            }
            if (kind == Relation.Kind.MANY_TO_ONE && subQueryExecutor != null) {
                RuntimePersistentEntity<?> associatedEntity = assoc.getAssociatedEntity();
                Map<String, Object> subFilterMap = Map.of(remaining, resolveOperators(operators, params, namedParameters));
                if (associatedEntity.hasCompositeIdentity()) {
                    return buildCompositeForwardLookupFilter(
                        entity, firstPart, associatedEntity, subFilterMap, params, namedParameters);
                }
                List<Object> matchingIds = subQueryExecutor.executeSubQuery(
                    associatedEntity, subFilterMap, null, false, params, namedParameters);
                return idsIn(fieldName, matchingIds);
            }
        }

        // Non-association property, unresolved path, or an association without a sub-query —
        // use persisted field name for dotted access.
        return operatorFiltersForPath.build(entity, fieldName + "." + remaining, operators, params, namedParameters);
    }

    private @Nullable Filter buildCollectionFilter(
            RuntimePersistentEntity<?> entity,
            RuntimeAssociation<?> assoc,
            String fieldName,
            String remaining,
            Map<String, Object> operators,
            Object[] params,
            Map<String, Object> namedParameters) {
        RuntimePersistentEntity<?> associatedEntity = assoc.getAssociatedEntity();
        if (subQueryExecutor == null) {
            Filter subFilter = fieldFilterProvider.build(associatedEntity, remaining, operators, params, namedParameters);
            return NitriteFilterUtils.elemMatch(fieldName, subFilter);
        }

        Map<String, Object> subFilterMap = Map.of(remaining, resolveOperators(operators, params, namedParameters));
        String mappedBy = assoc.getAnnotationMetadata().stringValue(Relation.class, "mappedBy").orElse(null);
        if (mappedBy == null) {
            if (assoc.getKind() != Relation.Kind.MANY_TO_MANY) {
                return none();
            }
            List<Object> matchingIds = subQueryExecutor.executeSubQuery(
                associatedEntity, subFilterMap, null, false, params, namedParameters);
            /*
             * The owning side stores the association as an array, which InFilter
             * matches by element containment, the same way its index path does.
             */
            return idsIn(fieldName, matchingIds);
        }

        RuntimePersistentProperty<?> backRefProp = associatedEntity.getPropertyByName(mappedBy);
        if (backRefProp == null) {
            return none();
        }
        return buildBackReferenceFilter(entity, associatedEntity, mappedBy, backRefProp, subFilterMap, params, namedParameters);
    }

    private Map<String, Object> resolveOperators(Map<String, Object> operators, Object[] params, Map<String, Object> namedParameters) {
        Map<String, Object> resolvedOperators = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : operators.entrySet()) {
            resolvedOperators.put(entry.getKey(), valueResolver.resolveValue(entry.getValue(), params, namedParameters));
        }
        return resolvedOperators;
    }

    private Filter buildCompositeForwardLookupFilter(
        RuntimePersistentEntity<?> entity,
        String associationName,
        RuntimePersistentEntity<?> associatedEntity,
        Map<String, Object> subFilterMap,
        Object[] params,
        Map<String, Object> namedParameters) {
        List<CompositeJoinColumn> joinColumns = entityMapper.getCompositeJoinColumns(
            entity.getIntrospection().getBeanType(), associationName);
        if (joinColumns.isEmpty()) {
            return none();
        }

        SubQueryExecutor executor = Objects.requireNonNull(subQueryExecutor);
        List<Object> matchingDocuments = executor.executeSubQuery(
            associatedEntity, subFilterMap, null, true, params, namedParameters);
        return anyRow(matchingDocuments, document -> forwardRow(document, joinColumns, associatedEntity));
    }

    /**
     * @return the owner-side key filter for one matched associated document, or {@code null}
     *         when the document lacks part of its identity
     */
    private @Nullable Filter forwardRow(Document document, List<CompositeJoinColumn> joinColumns,
                                        RuntimePersistentEntity<?> associatedEntity) {
        List<Filter> rowFilters = new ArrayList<>(joinColumns.size());
        for (CompositeJoinColumn joinColumn : joinColumns) {
            RuntimePersistentProperty<?> referenced = entityMapper.findPropertyByNameOrPersistedName(
                associatedEntity, joinColumn.referencedProperty());
            Object value = referenced == null ? null : document.get(referenced.getPersistedName());
            if (value == null) {
                return null;
            }
            rowFilters.add(NitriteFilterUtils.eq(joinColumn.localName(), value));
        }
        return allOf(rowFilters);
    }

    private Filter buildCompositeReverseLookupFilter(
        RuntimePersistentEntity<?> entity,
        RuntimePersistentEntity<?> associatedEntity,
        List<CompositeJoinColumn> joinColumns,
        Map<String, Object> subFilterMap,
        Object[] params,
        Map<String, Object> namedParameters) {
        SubQueryExecutor executor = Objects.requireNonNull(subQueryExecutor);
        List<Object> matchingDocuments = executor.executeSubQuery(
            associatedEntity, subFilterMap, null, true, params, namedParameters);
        return anyRow(matchingDocuments, document -> reverseRow(document, joinColumns, entity));
    }

    /**
     * @return the owner key filter for one matched referencing document, or {@code null} when
     *         the document does not carry a complete reference
     */
    private @Nullable Filter reverseRow(Document document, List<CompositeJoinColumn> joinColumns,
                                        RuntimePersistentEntity<?> entity) {
        List<Filter> rowFilters = new ArrayList<>(joinColumns.size());
        for (CompositeJoinColumn joinColumn : joinColumns) {
            RuntimePersistentProperty<?> referenced = entityMapper.findPropertyByNameOrPersistedName(
                entity, joinColumn.referencedProperty());
            if (referenced == null) {
                return null;
            }
            Object value = document.get(joinColumn.localName());
            if (value == null) {
                return null;
            }
            rowFilters.add(NitriteFilterUtils.eq(referenced.getPersistedName(), value));
        }
        return allOf(rowFilters);
    }

    private static Filter anyRow(List<Object> matchingDocuments, Function<Document, @Nullable Filter> rowFilter) {
        List<Filter> matchingRows = new ArrayList<>(matchingDocuments.size());
        for (Object matchingDocument : matchingDocuments) {
            Filter row = matchingDocument instanceof Document document ? rowFilter.apply(document) : null;
            if (row != null) {
                matchingRows.add(row);
            }
        }
        if (matchingRows.isEmpty()) {
            return none();
        }
        return matchingRows.size() == 1
            ? matchingRows.getFirst()
            : Filter.or(matchingRows.toArray(new Filter[0]));
    }

    private static Filter allOf(List<Filter> filters) {
        return filters.size() == 1
            ? filters.getFirst()
            : Filter.and(filters.toArray(new Filter[0]));
    }

    private static boolean isToMany(Relation.Kind kind) {
        return kind == Relation.Kind.ONE_TO_MANY || kind == Relation.Kind.MANY_TO_MANY;
    }

    private static Filter idsIn(String field, List<Object> values) {
        Comparable<?>[] ids = toComparableArray(values);
        return ids.length == 0 ? none() : NitriteFilterUtils.in(field, ids);
    }

    private boolean looksLikeId(String value, Class<?> idType) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (idType == UUID.class) {
            if (value.length() == 36 && value.charAt(8) == '-' && value.charAt(13) == '-'
                    && value.charAt(18) == '-' && value.charAt(23) == '-') {
                try {
                    UUID.fromString(value);
                    return true;
                } catch (IllegalArgumentException _) {
                    return false;
                }
            }
            return false;
        }
        return true;
    }

    private static Comparable<?>[] toComparableArray(List<Object> values) {
        return values.stream()
            .filter(Objects::nonNull)
            .map(id -> id instanceof Comparable<?> c ? c : id.toString())
            .toArray(Comparable<?>[]::new);
    }

    /**
     * @return a filter matching nothing, for a sub-query that selected no rows
     */
    private static Filter none() {
        return NitriteFilterUtils.matchNone();
    }

    @FunctionalInterface
    interface FieldFilterProvider {
        @Nullable Filter build(RuntimePersistentEntity<?> entity, String field, Map<String, Object> ops, Object[] params, Map<String, Object> named);
    }
}
