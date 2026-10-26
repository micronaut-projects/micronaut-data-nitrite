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
import io.micronaut.data.nitrite.model.query.NitriteInternalKeys;
import io.micronaut.data.nitrite.runtime.mapping.NitriteEntityMapper;
import io.micronaut.data.nitrite.runtime.mapping.NitriteTypeRegistry;
import io.micronaut.data.nitrite.runtime.query.ast.CompiledValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Internal
final class ValueResolver {

    private static final Logger LOG = LoggerFactory.getLogger(ValueResolver.class);

    private final NitriteEntityMapper entityMapper;

    ValueResolver(NitriteEntityMapper entityMapper) {
        this.entityMapper = entityMapper;
    }

    @Nullable Object resolveValue(@Nullable Object value, Object[] params, Map<String, Object> namedParameters) {
        Object resolved = resolveValueInternal(value, params, namedParameters);
        LOG.debug("resolveValue: value={}, resolved={}", value, resolved);
        return resolved;
    }

    @Nullable Object resolveValueInternal(@Nullable Object value, Object[] params, Map<String, Object> namedParameters) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            if (isSinglePositionalPlaceholder(s)) {
                Integer idx = parseIndex(s.substring(NitriteInternalKeys.QUERY_PARAMETER_PREFIX.length()));
                if (idx != null && inRange(params, idx)) {
                    return params[idx];
                }
            }
            if (s.contains(NitriteInternalKeys.QUERY_PARAMETER_PREFIX)) {
                return interpolateParameters(s, params);
            }
            if (s.startsWith(":") && namedParameters.containsKey(s.substring(1))) {
                return namedParameters.get(s.substring(1));
            }
        }
        if (value instanceof Map<?, ?> vm && vm.size() == 1
            && vm.get(NitriteInternalKeys.QUERY_PARAMETER_PLACEHOLDER) instanceof Integer idx
            && inRange(params, idx)) {
            return params[idx];
        }
        return value;
    }

    /**
     * Replaces every positional placeholder embedded in a string with its parameter's text. An
     * out-of-range placeholder is dropped; one whose index cannot be parsed is kept as written.
     *
     * @param s the string carrying placeholders
     * @param params positional query parameters
     * @return the interpolated string
     */
    private static String interpolateParameters(String s, Object[] params) {
        int prefixLength = NitriteInternalKeys.QUERY_PARAMETER_PREFIX.length();
        StringBuilder result = new StringBuilder();
        int pos = 0;
        int idx = s.indexOf(NitriteInternalKeys.QUERY_PARAMETER_PREFIX);
        while (idx >= 0) {
            result.append(s, pos, idx);
            int paramEnd = idx + prefixLength;
            while (paramEnd < s.length() && Character.isDigit(s.charAt(paramEnd))) {
                paramEnd++;
            }
            Integer paramIdx = parseIndex(s.substring(idx + prefixLength, paramEnd));
            if (paramIdx == null) {
                result.append(s, idx, paramEnd);
            } else if (inRange(params, paramIdx)) {
                Object paramValue = params[paramIdx];
                result.append(paramValue != null ? paramValue.toString() : "");
            }
            pos = paramEnd;
            idx = s.indexOf(NitriteInternalKeys.QUERY_PARAMETER_PREFIX, pos);
        }
        result.append(s, pos, s.length());
        return result.toString();
    }

    private static boolean isSinglePositionalPlaceholder(String s) {
        return s.startsWith(NitriteInternalKeys.QUERY_PARAMETER_PREFIX)
            && s.indexOf(NitriteInternalKeys.QUERY_PARAMETER_PREFIX, NitriteInternalKeys.QUERY_PARAMETER_PREFIX.length()) < 0;
    }

    private static @Nullable Integer parseIndex(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private static boolean inRange(@Nullable Object[] params, int idx) {
        return params != null && idx >= 0 && idx < params.length;
    }

    @Nullable Object preConvertForFilter(@Nullable Object value) {
        return NitriteTypeRegistry.write(value);
    }

    @Nullable Object maybeCoerceUuid(String field, @Nullable Object value) {
        if (value instanceof String s && ("id".equals(field) || "_id".equals(field))) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException _) {
                // Keep the original string if it is not a UUID.
            }
        }
        return value;
    }

    CompiledValue compileValue(Object value) {
        if (value instanceof String s) {
            Integer idx = isSinglePositionalPlaceholder(s)
                ? parseIndex(s.substring(NitriteInternalKeys.QUERY_PARAMETER_PREFIX.length()))
                : null;
            if (idx != null) {
                return new CompiledValue.Parameter(idx);
            }
            if (s.startsWith(":")) {
                return new CompiledValue.NamedParameter(s.substring(1));
            }
            if (s.contains(NitriteInternalKeys.QUERY_PARAMETER_PREFIX)) {
                return (params, named) -> resolveValueInternal(s, params, named);
            }
            return new CompiledValue.Literal(s);
        }
        if (value instanceof Map<?, ?> vm && vm.size() == 1
            && vm.get(NitriteInternalKeys.QUERY_PARAMETER_PLACEHOLDER) instanceof Integer idx) {
            return new CompiledValue.Parameter(idx);
        }
        return new CompiledValue.Literal(value);
    }

    List<Comparable<?>> resolveCollection(Object finalValue, Object[] params, Map<String, Object> namedParameters) {
        List<Comparable<?>> resolvedValues = new ArrayList<>();
        if (finalValue instanceof List<?> list && list.size() == 1 && isPlaceholder(list.getFirst())) {
            addBoundCollection(resolvedValues, resolveValue(list.getFirst(), params, namedParameters));
        } else if (finalValue instanceof Collection<?> coll) {
            for (Object item : coll) {
                addFilterValue(resolvedValues, resolveValue(item, params, namedParameters));
            }
        } else if (finalValue instanceof Object[] array) {
            for (Object item : array) {
                addFilterValue(resolvedValues, resolveValue(item, params, namedParameters));
            }
        }
        return resolvedValues;
    }

    /**
     * Adds the elements of a single bound parameter: a collection or array contributes each
     * element, any other comparable value contributes itself.
     */
    private void addBoundCollection(List<Comparable<?>> values, @Nullable Object resolved) {
        if (resolved instanceof Collection<?> coll) {
            for (Object item : coll) {
                addFilterValue(values, item);
            }
        } else if (resolved != null && resolved.getClass().isArray()) {
            int len = Array.getLength(resolved);
            for (int i = 0; i < len; i++) {
                addFilterValue(values, Array.get(resolved, i));
            }
        } else if (resolved instanceof Comparable<?> c) {
            values.add(c);
        }
    }

    private void addFilterValue(List<Comparable<?>> values, @Nullable Object item) {
        Object filterValue = entityMapper.toNitriteFilterValue(preConvertForFilter(item));
        if (filterValue instanceof Comparable<?> c) {
            values.add(c);
        }
    }

    private boolean isPlaceholder(Object value) {
        if (value instanceof String s
            && (s.startsWith(NitriteInternalKeys.QUERY_PARAMETER_PREFIX) || s.startsWith(":"))) {
            return true;
        }
        return value instanceof Map<?, ?> vm && vm.size() == 1
            && vm.containsKey(NitriteInternalKeys.QUERY_PARAMETER_PLACEHOLDER);
    }
}
