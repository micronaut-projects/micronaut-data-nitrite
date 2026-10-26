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

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.annotation.sql.JoinColumn;
import io.micronaut.data.model.Association;
import io.micronaut.data.model.PersistentEntity;
import io.micronaut.data.model.PersistentPropertyPath;
import io.micronaut.data.model.jpa.criteria.PersistentEntityRoot;
import io.micronaut.data.model.jpa.criteria.impl.CriteriaUtils;
import io.micronaut.data.model.jpa.criteria.impl.expression.IdExpression;
import io.micronaut.data.model.jpa.criteria.impl.expression.UnaryExpression;
import io.micronaut.data.model.jpa.criteria.impl.selection.AliasedSelection;
import io.micronaut.data.model.jpa.criteria.impl.selection.CompoundSelection;
import io.micronaut.data.model.query.JoinPath;
import io.micronaut.data.nitrite.model.query.NitriteQueryOperators;
import jakarta.persistence.criteria.Selection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.AND;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.EQ;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.EXPR;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.LOOKUP;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.MATCH;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.UNWIND;

/**
 * Helper for Nitrite query building that handles complex structural logic.
 * This class is intended for use by both the Annotation Processor and Runtime,
 * but its complexity is primarily validated by the AP's static analysis.
 *
 */
@Internal
final class NitriteQueryBuilderHelper {

    private static final String COUNT_RESULT = "result";

    private NitriteQueryBuilderHelper() {
    }

    /**
     * Appends Nitrite lookup and unwind stages to the aggregation pipeline for the given join paths.
     * This method resolves the complex structural logic of entity associations (e.g. single vs multiple
     * `@JoinColumn` annotations, explicit foreign keys, and mapped-by relations), synthesizing the appropriate
     * `$lookup` and `$unwind` document pipeline stages for the Nitrite query.
     *
     * @param joins the collection of join paths to process
     * @param rootEntity the root persistent entity of the query
     * @param pipeline the aggregation pipeline to append the lookup stages to
     */
    public static void addLookups(Collection<JoinPath> joins, PersistentEntity rootEntity, List<Map<String, Object>> pipeline) {
        if (joins == null || joins.isEmpty()) {
            return;
        }
        List<String> sorted = joins.stream().map(JoinPath::getPath)
            .sorted((a, b) -> a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b))
            .toList();
        Map<String, LookupsStage> subLookupMap = new HashMap<>();
        for (String join : sorted) {
            addLookupPath(join, rootEntity, pipeline, subLookupMap);
        }
    }

    private static void addLookupPath(String join, PersistentEntity rootEntity,
                                       List<Map<String, Object>> pipeline, Map<String, LookupsStage> subLookupMap) {
        PersistentEntity currentEntity = rootEntity;
        List<Map<String, Object>> currentPipeline = pipeline;
        Map<String, LookupsStage> currentSubLookups = subLookupMap;
        StringJoiner processedPath = new StringJoiner(".");
        for (String segment : join.split("\\.", -1)) {
            processedPath.add(segment);
            String pathKey = processedPath.toString();
            LookupsStage stage = currentSubLookups.get(pathKey);
            if (stage == null) {
                PersistentPropertyPath propPath = currentEntity.getPropertyPath(segment);
                if (propPath != null && propPath.getProperty() instanceof Association association && !association.isEmbedded()) {
                    stage = addAssociationLookup(association, segment, currentPipeline);
                    if (stage != null) {
                        currentSubLookups.put(pathKey, stage);
                    }
                }
            }
            if (stage != null) {
                currentPipeline = stage.pipeline;
                currentSubLookups = stage.subLookups;
                currentEntity = stage.entity;
            }
        }
    }

    private static @Nullable LookupsStage addAssociationLookup(Association association, String segment,
                                                                List<Map<String, Object>> pipeline) {
        LookupsStage stage = new LookupsStage(association.getAssociatedEntity());
        String joinedCollection = association.getAssociatedEntity().getPersistedName();
        // Read repeated values: the annotation processor does not synthesize a JoinColumns container.
        List<AnnotationValue<JoinColumn>> joinColumnValues =
            association.getAnnotationMetadata().getAnnotationValuesByType(JoinColumn.class);
        if (joinColumnValues.size() > 1) {
            List<String> localFields = new ArrayList<>();
            List<String> foreignFields = new ArrayList<>();
            for (var joinColumn : joinColumnValues) {
                localFields.add(joinColumn.stringValue("name").orElse(segment));
                foreignFields.add(joinColumn.stringValue("referencedColumnName").orElse("_id"));
            }
            pipeline.add(lookup(joinedCollection, localFields, foreignFields, stage.pipeline, segment));
            return stage;
        }
        boolean hasMappedBy = association.getAnnotationMetadata().stringValue(Relation.class, "mappedBy").isPresent();
        if (association.isForeignKey() || hasMappedBy) {
            String mappedBy = association.getAnnotationMetadata().stringValue(Relation.class, "mappedBy").orElse(null);
            if (mappedBy == null) {
                return null;
            }
            PersistentPropertyPath backPropPath = association.getAssociatedEntity().getPropertyPath(mappedBy);
            if (backPropPath == null) {
                return null;
            }
            String foreignField = backPropPath.getProperty().getPersistedName();
            pipeline.add(lookup(joinedCollection, "_id", foreignField, stage.pipeline, segment));
        } else {
            pipeline.add(lookup(joinedCollection, association.getPersistedName(), "_id", stage.pipeline, segment));
            if (association.getKind().isSingleEnded()) {
                pipeline.add(unwind("$" + segment));
            }
        }
        return stage;
    }

    private static Map<String, Object> lookup(String from, List<String> localFields, List<String> foreignFields,
                                               List<Map<String, Object>> pipeline, String as) {
        if (localFields.size() == 1) {
            return lookup(from, localFields.getFirst(), foreignFields.getFirst(), pipeline, as);
        }
        Map<String, Object> let = new LinkedHashMap<>();
        List<Map<String, Object>> matches = new ArrayList<>();
        int i = 1;
        for (int j = 0; j < localFields.size(); j++) {
            String variableName = "v" + i++;
            let.put(variableName, "$" + localFields.get(j));
            matches.add(Map.of(EQ, List.of("$$" + variableName, "$" + foreignFields.get(j))));
        }
        // size() is always > 1 here: the single-field case returns above via the
        // localFields.size() == 1 guard, so matches always holds at least two predicates.
        pipeline.addFirst(Map.of(MATCH, Map.of(EXPR, Map.of(AND, matches))));
        Map<String, Object> lookupDoc = new LinkedHashMap<>();
        lookupDoc.put("from", from); lookupDoc.put("let", let); lookupDoc.put("pipeline", pipeline); lookupDoc.put("as", as);
        return Map.of(LOOKUP, lookupDoc);
    }

    private static Map<String, Object> lookup(String from, String localField, String foreignField,
                                               List<Map<String, Object>> pipeline, String as) {
        Map<String, Object> lookupDoc = new LinkedHashMap<>();
        lookupDoc.put("from", from); lookupDoc.put("localField", localField);
        lookupDoc.put("foreignField", foreignField);
        lookupDoc.put("pipeline", pipeline);  // always include so nested lookups added later are reflected
        lookupDoc.put("as", as);
        return Map.of(LOOKUP, lookupDoc);
    }

    private static Map<String, Object> unwind(String path) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("path", path); u.put("preserveNullAndEmptyArrays", true);
        return Map.of(UNWIND, u);
    }

    /**
     * Builds aggregation group and count projection stages based on the given selection.
     * Mutates the provided group and count objects with the corresponding Nitrite operations
     * (`$sum`, `$avg`, `$max`, `$min`, `$count`) mapped from the generic Criteria API expressions.
     *
     * @param selection the generic selection criteria (e.g., SUM, AVG, COUNT)
     * @param group the group map to populate with aggregation operations
     * @param projectionObj the projection map to populate if a scalar property projection is present
     * @param countObj the count map to populate if a count projection is present
     */
    public static void buildProjection(Selection<?> selection,
                                       Map<String, Object> group,
                                       Map<String, Object> projectionObj,
                                       Map<String, Object> countObj) {
        if (selection == null) {
            return;
        }
        switch (selection) {
            case AliasedSelection<?> aliasedSelection ->
                buildProjection(aliasedSelection.getSelection(), group, projectionObj, countObj);
            case io.micronaut.data.model.jpa.criteria.PersistentPropertyPath<?> propertyPath ->
                projectionObj.put(persistedPath(propertyPath.getPropertyPath()), 1);
            case IdExpression<?, ?> idExpression -> {
                PersistentEntity persistentEntity = idExpression.getRoot().getPersistentEntity();
                if (!persistentEntity.hasIdentity() || persistentEntity.hasCompositeIdentity()) {
                    throw new IllegalStateException("Cannot project id() for entity without a single identity: " + persistentEntity.getName());
                }
                projectionObj.put(persistentEntity.getIdentity().getName(), 1);
            }
            case UnaryExpression<?> unary -> {
                switch (unary.getType()) {
                    case SUM, AVG, MAX, MIN -> {
                        PersistentPropertyPath propertyPath = CriteriaUtils.requireProperty(unary.getExpression()).getPropertyPath();
                        String op = switch (unary.getType()) {
                            case SUM -> NitriteQueryOperators.SUM;
                            case AVG -> NitriteQueryOperators.AVG;
                            case MAX -> NitriteQueryOperators.MAX;
                            case MIN -> NitriteQueryOperators.MIN;
                            default ->
                                throw new IllegalStateException("Unexpected: " + unary.getType());
                        };
                        group.put(propertyPath.getProperty().getName(), Map.of(op, "$" + persistedPath(propertyPath)));
                    }
                    case COUNT -> countObj.put(NitriteQueryOperators.COUNT, COUNT_RESULT);
                    case COUNT_DISTINCT -> {
                        if (unary.getExpression() instanceof PersistentEntityRoot) {
                            countObj.put(NitriteQueryOperators.COUNT, COUNT_RESULT);
                        } else {
                            PersistentPropertyPath propertyPath = CriteriaUtils.requireProperty(unary.getExpression()).getPropertyPath();
                            group.put("_id", "$" + persistedPath(propertyPath));
                            countObj.put(NitriteQueryOperators.COUNT, COUNT_RESULT);
                        }
                    }
                    default -> { /* ignore */ }
                }
            }
            case CompoundSelection<?> compound -> {
                for (Selection<?> item : compound.getCompoundSelectionItems()) {
                    buildProjection(item, group, projectionObj, countObj);
                }
            }
            default -> {
                // Other selection kinds do not contribute group, count or property projections.
            }
        }
    }

    private static String persistedPath(PersistentPropertyPath propertyPath) {
        StringJoiner path = new StringJoiner(".");
        for (Association association : propertyPath.getAssociations()) {
            path.add(association.getPersistedName());
        }
        path.add(propertyPath.getProperty().getPersistedName());
        return path.toString();
    }

    private static final class LookupsStage {
        final PersistentEntity entity;
        final List<Map<String, Object>> pipeline = new ArrayList<>();
        final Map<String, LookupsStage> subLookups = new HashMap<>();

        LookupsStage(PersistentEntity entity) {
            this.entity = entity;
        }
    }
}
