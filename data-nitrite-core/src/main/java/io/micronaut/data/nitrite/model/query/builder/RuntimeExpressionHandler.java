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

import io.micronaut.data.model.PersistentPropertyPath;
import io.micronaut.data.model.query.BindingParameter;
import io.micronaut.data.model.jpa.criteria.impl.expression.LiteralExpression;
import io.micronaut.data.nitrite.model.query.NitriteInternalKeys;
import io.micronaut.data.nitrite.runtime.ValueConverter;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import jakarta.persistence.criteria.Expression;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Runtime implementation of {@link NitriteExpressionHandler} that prioritizes parameter binding.
 *
 * @since 5.2.0
 */
@Internal
public final class RuntimeExpressionHandler implements NitriteExpressionHandler {

    /**
     * Creates a new instance of RuntimeExpressionHandler for runtime parameter binding.
     */
    public RuntimeExpressionHandler() {
        // Expression translation is stateless; no instance setup is needed.
    }

    @Override
    public @Nullable Object resolveValue(NitriteQueryState queryState, PersistentPropertyPath propertyPath, @Nullable Object value) {
        if (value instanceof BindingParameter bindingParameter) {
            BindingParameter.BindingContext context = NitritePredicateVisitor.newBindingContext(propertyPath, propertyPath);
            int index = queryState.pushParameter(bindingParameter, context);
            return Map.of(NitriteInternalKeys.QUERY_PARAMETER_PLACEHOLDER, index);
        }
        if (value instanceof LiteralExpression<?> literal) {
            return ValueConverter.toFilterValueStatic(unwrapLiteral(literal));
        }
        if (value instanceof Expression<?> expr) {
            // Recurse or return as-is for runtime handling
            return expr;
        }
        return ValueConverter.toFilterValueStatic(value);
    }

    @Override
    public @Nullable Object handleRegex(
        boolean ignoreCase,
        boolean startsWith,
        boolean endsWith,
        @Nullable Expression<?> rightExpression,
        NitriteQueryState queryState,
        PersistentPropertyPath propertyPath) {

        Object paramPlaceholder = rightExpression instanceof LiteralExpression<?> literal
            ? ValueConverter.toFilterValueStatic(unwrapLiteral(literal))
            : resolveValue(queryState, propertyPath, rightExpression);

        if (startsWith && !endsWith && !ignoreCase) {
            // A plain prefix stays an indexed range rather than a per-document regex scan.
            Map<String, Object> range = LinkedHashMap.newLinkedHashMap(1);
            range.put(NitriteInternalKeys.RANGE_PREFIX, paramPlaceholder);
            return range;
        }
        boolean parameterPlaceholder = paramPlaceholder instanceof Map<?, ?> map
            && map.containsKey(NitriteInternalKeys.QUERY_PARAMETER_PLACEHOLDER);
        if (parameterPlaceholder) {
            return regexDescriptor(paramPlaceholder, startsWith, endsWith, ignoreCase);
        }
        String ciPrefix = ignoreCase ? "(?i)" : "";
        String prefix = startsWith ? "^" : ".*";
        String suffix = endsWith ? "$" : ".*";
        String value = paramPlaceholder != null ? paramPlaceholder.toString() : "";
        // Quote user values; only generated anchors and wildcards remain unquoted.
        return ciPrefix + prefix + Pattern.quote(value) + suffix;
    }

    private static Map<String, Object> regexDescriptor(@Nullable Object value,
                                                        boolean startsWith, boolean endsWith, boolean ignoreCase) {
        // Binding owns quoting because the parameter's value is not yet known.
        Map<String, Object> pattern = LinkedHashMap.newLinkedHashMap(4);
        pattern.put(NitriteInternalKeys.REGEX_PATTERN, value);
        if (startsWith) {
            pattern.put(NitriteInternalKeys.REGEX_STARTS_WITH, true);
        }
        if (endsWith) {
            pattern.put(NitriteInternalKeys.REGEX_ENDS_WITH, true);
        }
        if (ignoreCase) {
            pattern.put(NitriteInternalKeys.REGEX_IGNORE_CASE, true);
        }
        return pattern;
    }

    @Override
    public @Nullable Object resolveRegexValue(NitriteQueryState queryState, PersistentPropertyPath propertyPath, @Nullable Expression<?> expression) {
        return resolveValue(queryState, propertyPath, expression);
    }

    @Override
    public List<Object> resolveCollectionValue(NitriteQueryState queryState, PersistentPropertyPath propertyPath, @Nullable Expression<?> expression) {
        Object rawValue = expression instanceof LiteralExpression<?> lit ? unwrapLiteral(lit) : expression;
        if (rawValue instanceof Iterable<?> iterable) {
            List<Object> criteriaValues = new ArrayList<>();
            for (Object item : iterable) {
                Object itemVal = item instanceof Expression<?> ? item : new LiteralExpression<>(item);
                criteriaValues.add(resolveValue(queryState, propertyPath, itemVal));
            }
            return criteriaValues;
        }
        Object resolved = resolveValue(queryState, propertyPath, expression);
        List<Object> criteriaValues = new ArrayList<>(1);
        criteriaValues.add(resolved);
        return criteriaValues;
    }

    private static @Nullable Object unwrapLiteral(LiteralExpression<?> literal) {
        Object value = literal.getValue();
        while (value instanceof LiteralExpression<?> nested) {
            value = nested.getValue();
        }
        return value;
    }
}
