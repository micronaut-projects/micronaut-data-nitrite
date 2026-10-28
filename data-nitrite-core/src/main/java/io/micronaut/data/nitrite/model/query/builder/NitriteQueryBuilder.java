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
package io.micronaut.data.nitrite.model.query.builder;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.TypeHint;
import io.micronaut.data.model.PersistentEntity;
import io.micronaut.data.model.PersistentProperty;
import io.micronaut.data.model.Sort;
import io.micronaut.data.model.jpa.criteria.IPredicate;
import io.micronaut.data.model.jpa.criteria.PersistentPropertyPath;
import io.micronaut.data.model.jpa.criteria.impl.CriteriaUtils;
import io.micronaut.data.model.jpa.criteria.impl.expression.BinaryExpression;
import io.micronaut.data.model.jpa.criteria.impl.expression.LiteralExpression;
import io.micronaut.data.model.query.BindingParameter;
import io.micronaut.data.model.query.builder.QueryBuilder;
import io.micronaut.data.model.query.builder.QueryParameterBinding;
import io.micronaut.data.model.query.builder.QueryResult;
import io.micronaut.data.nitrite.model.query.NitriteInternalKeys;
import io.micronaut.data.nitrite.model.query.NitriteQueryOperators;
import io.micronaut.data.nitrite.model.query.builder.compile.CompileExpressionHandler;
import io.micronaut.data.runtime.config.DataSettings;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Nulls;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds Nitrite JSON filter queries from Micronaut Data criteria expressions.
 * Generates JSON that is interpreted at runtime by {@link io.micronaut.data.nitrite.runtime.query.NitriteFilterBuilder}.
 *
 * <p><strong>Note on explicitly defined queries:</strong> This builder and the underlying Nitrite engine
 * explicitly do <strong>not</strong> support SQL strings. The legacy SQL-parsing support present in
 * older versions (4.14.x) has been entirely removed. Any explicitly defined {@code @Query} must use
 * Nitrite's JSON filter syntax.
 *
 * @since 5.2.0
 */
@Internal
@Introspected
@TypeHint(NitriteQueryBuilder.class)
public final class NitriteQueryBuilder implements QueryBuilder {

    private final AnnotationMetadata queryBuilderMetadata;

    /**
     * Creates a new NitriteQueryBuilder.
     */
    public NitriteQueryBuilder() {
        this(AnnotationMetadata.EMPTY_METADATA);
    }

    /**
     * Creates a new NitriteQueryBuilder with annotation metadata.
     *
     * @param annotationMetadata the annotation metadata
     */
    public NitriteQueryBuilder(AnnotationMetadata annotationMetadata) {
        this.queryBuilderMetadata = annotationMetadata;
    }

    @Override
    public QueryResult buildInsert(
        final AnnotationMetadata repositoryMetadata, final InsertQueryDefinition definition) {
        return QueryResult.of("", List.of(), List.of(), Map.of());
    }

    @Override
    public QueryResult buildSelect(
        @NonNull final AnnotationMetadata annotationMetadata,
        @NonNull final SelectQueryDefinition query) {
        if (DataSettings.QUERY_LOG.isDebugEnabled()) {
            DataSettings.QUERY_LOG.debug("buildSelect: entity={}, predicate={}", query.persistentEntity().getName(), query.predicate());
        }
        SelectParts parts = selectParts(query);
        Object sortObj = parts.sortKeys().isEmpty() ? null : NitriteSortKeys.toJson(parts.sortKeys());

        boolean needsPipeline = parts.needsPipeline() || (sortObj != null && !parts.predicateObj().isEmpty());
        String queryString;
        if (needsPipeline) {
            List<Map<String, Object>> pipeline = pipelineStages(parts.lookupPipeline(), parts.predicateObj(), parts.group(), parts.countObj());
            selectStages(sortObj, parts.projectionObj(), query).forEach((operator, operand) -> pipeline.add(Map.of(operator, operand)));
            queryString = NitriteQuerySerializer.toJsonString(pipeline);
        } else {
            Map<String, Object> topLevel = new LinkedHashMap<>(parts.predicateObj());
            topLevel.putAll(selectStages(sortObj, parts.projectionObj(), query));
            queryString = topLevel.isEmpty() ? "{}" : NitriteQuerySerializer.toJsonString(topLevel);
        }
        return QueryResult.of(
            queryString,
            List.of(),
            parts.queryState().getParameterBindings(),
            Map.of(),
            query.limit(),
            query.offset(),
            query.getJoinPaths());
    }

    private static List<Map<String, Object>> pipelineStages(List<Map<String, Object>> lookupPipeline, Map<String, Object> predicateObj,
                                                            Map<String, Object> group, Map<String, Object> countObj) {
        List<Map<String, Object>> pipeline = new ArrayList<>(lookupPipeline);
        if (!predicateObj.isEmpty()) {
            pipeline.add(Map.of(NitriteQueryOperators.MATCH, predicateObj));
        }
        if (!group.isEmpty()) {
            group.putIfAbsent("_id", null);
            pipeline.add(Map.of(NitriteQueryOperators.GROUP, group));
        }
        if (!countObj.isEmpty()) {
            pipeline.add(countObj);
        }
        return pipeline;
    }

    /**
     * Collects the sort, projection, skip and limit operators that close a select, in that order.
     * The pipeline form wraps each as its own stage; the top-level form merges them into the query.
     *
     * @param sortObj the sort document, {@code null} when unsorted
     * @param projectionObj the projection document
     * @param query the select definition carrying offset and limit
     * @return the operators keyed by name, in emission order
     */
    private static Map<String, Object> selectStages(@Nullable Object sortObj, Map<String, Object> projectionObj, SelectQueryDefinition query) {
        Map<String, Object> stages = new LinkedHashMap<>();
        if (sortObj != null) {
            stages.put(NitriteQueryOperators.SORT, sortObj);
        }
        if (!projectionObj.isEmpty()) {
            stages.put(NitriteQueryOperators.PROJECT, projectionObj);
        }
        if (query.offset() > 0) {
            stages.put(NitriteQueryOperators.SKIP, query.offset());
        }
        if (query.limit() != -1) {
            stages.put(NitriteQueryOperators.LIMIT, query.limit());
        }
        return stages;
    }

    /**
     * Builds the runtime filter directly from a select definition, skipping the JSON
     * serialize/reparse round trip {@link #buildSelect} needs to satisfy the shared
     * {@link QueryResult} contract (a compiled query must survive into generated bytecode as a
     * {@code String}; the runtime Criteria path has no such requirement).
     *
     * @param query the select definition
     * @return the runtime filter, or {@code null} if the query needs the aggregation pipeline
     *         (joins, group, count) — callers should fall back to {@link #buildSelect} for that case
     */
    @Nullable
    public NitriteRuntimeFilter buildRuntimeFilter(@NonNull final SelectQueryDefinition query) {
        SelectParts parts = selectParts(query);
        if (parts.needsPipeline()) {
            return null;
        }
        return new NitriteRuntimeFilter(parts.predicateObj(), parts.sortKeys(), parts.projectionObj(), query.offset(), query.limit(),
            parts.queryState().getParameterBindings());
    }

    private SelectParts selectParts(SelectQueryDefinition query) {
        NitriteQueryState queryState = new NitriteQueryState(query.persistentEntity());
        List<Map<String, Object>> lookupPipeline = new ArrayList<>();
        NitriteQueryBuilderHelper.addLookups(query.getJoinPaths(), query.persistentEntity(), lookupPipeline);

        Map<String, Object> predicateObj = new LinkedHashMap<>();
        Map<String, Object> group = new LinkedHashMap<>();
        Map<String, Object> countObj = new LinkedHashMap<>();
        Map<String, Object> projectionObj = new LinkedHashMap<>();

        Predicate predicate = query.predicate();
        if (predicate != null) {
            predicateObj = buildWhereClauseFromCriteria(predicate, queryState);
        }

        NitriteQueryBuilderHelper.buildProjection(query.selection(), group, projectionObj, countObj);

        List<NitriteSortKey> sortKeys = buildSortKeys(query.order(), queryState);
        return new SelectParts(queryState, lookupPipeline, predicateObj, group, countObj, projectionObj, sortKeys);
    }

    @Override
    public QueryResult buildUpdate(
        @NonNull final AnnotationMetadata annotationMetadata,
        @NonNull final UpdateQueryDefinition definition) {
        NitriteQueryState queryState = new NitriteQueryState(definition.persistentEntity());
        Predicate predicate = definition.predicate();
        Map<String, Object> predicateObj = new LinkedHashMap<>();
        if (predicate != null) {
            predicateObj = buildWhereClauseFromCriteria(predicate, queryState);
        }

        Map<String, Map<String, Object>> operations = new LinkedHashMap<>();
        for (String operator : List.of(NitriteQueryOperators.SET, NitriteQueryOperators.INC, NitriteQueryOperators.MUL, NitriteQueryOperators.CONCAT)) {
            operations.put(operator, new LinkedHashMap<>());
        }
        Map<String, Object> propertiesToUpdate = definition.propertiesToUpdate();
        if (propertiesToUpdate != null) {
            for (Map.Entry<String, Object> entry : propertiesToUpdate.entrySet()) {
                putUpdate(queryState, definition.persistentEntity(), entry.getKey(), entry.getValue(), operations);
            }
        }

        String predicateString = predicateObj.isEmpty() ? "{}" : NitriteQuerySerializer.toJsonString(predicateObj);
        Map<String, Object> updateObj = new LinkedHashMap<>();
        operations.forEach((operator, fields) -> {
            if (!fields.isEmpty()) {
                updateObj.put(operator, fields);
            }
        });
        String updateString = updateObj.isEmpty() ? null : NitriteQuerySerializer.toJsonString(updateObj);
        List<QueryParameterBinding> parameterBindings = queryState.getParameterBindings();
        return new QueryResult() {
            @Override
            public String getQuery() {
                return predicateString;
            }

            @Override
            public @Nullable String getUpdate() {
                return updateString;
            }

            @Override
            public List<String> getQueryParts() {
                return List.of();
            }

            @Override
            public List<QueryParameterBinding> getParameterBindings() {
                return parameterBindings;
            }
        };
    }

    private void putUpdate(NitriteQueryState queryState, PersistentEntity entity, String fieldName, Object value,
                           Map<String, Map<String, Object>> operations) {
        PersistentProperty property = entity.getPropertyByName(fieldName);
        String persistedName = property != null ? property.getPersistedName() : fieldName;
        if (value instanceof BinaryExpression<?> binaryExpression) {
            putArithmeticUpdate(queryState, property, persistedName, binaryExpression, operations);
        } else {
            operationFields(operations, NitriteQueryOperators.SET)
                .put(persistedName, updateExpressionValue(queryState, property, value));
        }
    }

    private Map<String, Object> operationFields(Map<String, Map<String, Object>> operations, String operator) {
        return Objects.requireNonNull(operations.get(operator), "Missing update operator: " + operator);
    }

    /**
     * Files one arithmetic or concatenation update under its operator. Subtraction and division
     * reuse {@code $inc} and {@code $mul} with a flag the runtime applies to the operand.
     *
     * @param queryState the query state collecting parameter bindings
     * @param property the property being updated, {@code null} when it is not mapped
     * @param persistedName the document field being updated
     * @param binaryExpression the update expression
     * @param operations the update documents keyed by operator
     */
    private void putArithmeticUpdate(NitriteQueryState queryState, @Nullable PersistentProperty property, String persistedName,
                                     BinaryExpression<?> binaryExpression, Map<String, Map<String, Object>> operations) {
        PersistentPropertyPath<?> leftProperty = CriteriaUtils.requireProperty(binaryExpression.getLeft());
        if (property != null && !leftProperty.getProperty().getName().equals(property.getName())) {
            throw new IllegalStateException("Left property path does not match property path");
        }
        Object updateValue = Objects.requireNonNull(
            updateExpressionValue(queryState, property, binaryExpression.getRight()),
            () -> "Null operand in arithmetic update of property: " + persistedName);
        switch (binaryExpression.getType()) {
            case CONCAT -> operationFields(operations, NitriteQueryOperators.CONCAT).put(persistedName, updateValue);
            case SUM -> operationFields(operations, NitriteQueryOperators.INC).put(persistedName, updateValue);
            case PROD -> operationFields(operations, NitriteQueryOperators.MUL).put(persistedName, updateValue);
            case QUOT -> operationFields(operations, NitriteQueryOperators.MUL).put(persistedName, withFlag(updateValue, NitriteInternalKeys.RECIPROCATE));
            case DIFF -> operationFields(operations, NitriteQueryOperators.INC).put(persistedName, withFlag(updateValue, NitriteInternalKeys.NEGATE));
            default -> throw new IllegalStateException("Unsupported binary expression type: " + binaryExpression.getType());
        }
    }

    /**
     * Resolves the value written for one updated property: a bound parameter becomes a placeholder
     * the runtime resolves later, a literal is unwrapped to the value it carries, and anything else
     * is written as-is.
     *
     * @param queryState the query state collecting parameter bindings
     * @param property the property being updated, {@code null} when it is not mapped
     * @param value the raw value from the update definition
     * @return the value to write into the update document
     */
    private @Nullable Object updateExpressionValue(NitriteQueryState queryState, @Nullable PersistentProperty property, Object value) {
        if (value instanceof BindingParameter bindingParameter) {
            int index = queryState.pushParameter(bindingParameter, NitritePredicateVisitor.newBindingContext(property));
            return Map.of(NitriteInternalKeys.QUERY_PARAMETER_PLACEHOLDER, index);
        }
        if (value instanceof LiteralExpression<?> literalExpression) {
            return literalExpression.getValue();
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Object withFlag(Object value, String flag) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> flagged = new LinkedHashMap<>((Map<String, Object>) map);
            flagged.put(flag, true);
            return flagged;
        }
        return Map.of(NitriteQueryOperators.VALUE, value, flag, true);
    }

    @Override
    public QueryResult buildDelete(
        @NonNull final AnnotationMetadata annotationMetadata,
        @NonNull final DeleteQueryDefinition definition) {
        NitriteQueryState queryState = new NitriteQueryState(definition.persistentEntity());
        Predicate predicate = definition.predicate();
        Map<String, Object> predicateObj = new LinkedHashMap<>();
        if (predicate != null) {
            predicateObj = buildWhereClauseFromCriteria(predicate, queryState);
        }
        String queryString =
            predicateObj.isEmpty() ? "{}" : NitriteQuerySerializer.toJsonString(predicateObj);
        return QueryResult.of(
            queryString,
            List.of(),
            queryState.getParameterBindings());
    }

    @Override
    @NonNull
    public String buildLimitAndOffset(final long limit, final long offset) {
        Map<String, Object> obj = new LinkedHashMap<>();
        if (offset > 0) {
            obj.put(NitriteQueryOperators.SKIP, (int) offset);
        }
        if (limit > 0) {
            obj.put(NitriteQueryOperators.LIMIT, (int) limit);
        }
        return obj.isEmpty() ? "{}" : NitriteQuerySerializer.toJsonString(obj);
    }

    /**
     * Translates the {@code ORDER BY} of a query into sort keys. A key over a plain property stays
     * a stored field name; anything computed is encoded as the same {@code $expr} operand tree a
     * predicate over that expression would produce, and is evaluated per document at read time.
     */
    private List<NitriteSortKey> buildSortKeys(final List<Order> orders, final NitriteQueryState queryState) {
        if (orders.isEmpty()) {
            return List.of();
        }
        List<NitriteSortKey> keys = new ArrayList<>(orders.size());
        NitritePredicateVisitor expressionVisitor = null;
        for (Order order : orders) {
            Sort.Order.NullOrdering nullOrdering = nullOrdering(order);
            Expression<?> expression = order.getExpression();
            if (expression instanceof PersistentPropertyPath<?> propertyPath) {
                keys.add(NitriteSortKey.ofField(
                    NitriteFieldNameResolver.getFieldName(propertyPath.getPropertyPath()),
                    order.isAscending(),
                    nullOrdering));
            } else {
                if (expressionVisitor == null) {
                    expressionVisitor = new NitritePredicateVisitor(queryState, new LinkedHashMap<>(), expressionHandler());
                }
                keys.add(NitriteSortKey.ofExpression(
                    expressionVisitor.encodeExpression(expression), order.isAscending(), nullOrdering));
            }
        }
        return keys;
    }

    private static Sort.Order.NullOrdering nullOrdering(final Order order) {
        Nulls nullPrecedence = order.getNullPrecedence();
        if (nullPrecedence == null) {
            return Sort.Order.NullOrdering.NONE;
        }
        return switch (nullPrecedence) {
            case FIRST -> Sort.Order.NullOrdering.FIRST;
            case LAST -> Sort.Order.NullOrdering.LAST;
            case NONE -> Sort.Order.NullOrdering.NONE;
        };
    }

    private NitriteExpressionHandler expressionHandler() {
        return !queryBuilderMetadata.equals(AnnotationMetadata.EMPTY_METADATA)
            ? new CompileExpressionHandler()
            : new RuntimeExpressionHandler();
    }

    private Map<String, Object> buildWhereClauseFromCriteria(
        final Predicate predicate, final NitriteQueryState queryState) {
        if (predicate == null) {
            return Map.of();
        }
        Map<String, Object> queryMap = new LinkedHashMap<>();
        if (predicate instanceof IPredicate predicateVisitable) {
            predicateVisitable.visitPredicate(new NitritePredicateVisitor(queryState, queryMap, expressionHandler()));
        } else {
            throw new IllegalStateException(
                "Unsupported predicate type: " + predicate.getClass().getName());
        }
        return queryMap;
    }

    /**
     * The predicate, projection, lookups and sort keys of a select, shared by {@link #buildSelect}
     * and {@link #buildRuntimeFilter}.
     *
     * @param queryState the state collecting the parameter bindings
     * @param lookupPipeline the join lookup stages
     * @param predicateObj the where clause
     * @param group the group stage
     * @param countObj the count stage
     * @param projectionObj the projection document
     * @param sortKeys the sort keys
     */
    private record SelectParts(NitriteQueryState queryState, List<Map<String, Object>> lookupPipeline,
                               Map<String, Object> predicateObj, Map<String, Object> group, Map<String, Object> countObj,
                               Map<String, Object> projectionObj, List<NitriteSortKey> sortKeys) {

        /**
         * Joins, grouping and counting only run as an aggregation pipeline.
         */
        boolean needsPipeline() {
            return !lookupPipeline.isEmpty() || !group.isEmpty() || !countObj.isEmpty();
        }
    }
}
