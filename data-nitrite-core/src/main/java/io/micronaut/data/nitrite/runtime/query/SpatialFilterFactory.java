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
import io.micronaut.data.exceptions.DataAccessException;
import io.micronaut.data.nitrite.runtime.mapping.NitriteEntityMapper;
import io.micronaut.data.nitrite.spi.NitriteProviders;
import io.micronaut.data.nitrite.spi.NitriteSpatialSupport;
import org.dizitart.no2.filters.Filter;

import java.util.List;
import java.util.Map;

/**
 * Resolves query values before delegating optional spatial operations.
 */
@Internal
final class SpatialFilterFactory {
    private final NitriteEntityMapper entityMapper;
    private final ValueResolver valueResolver;
    private final List<NitriteSpatialSupport> providers = NitriteProviders.load(NitriteSpatialSupport.class);

    SpatialFilterFactory(NitriteEntityMapper entityMapper, ValueResolver valueResolver) {
        this.entityMapper = entityMapper;
        this.valueResolver = valueResolver;
    }

    Filter buildNearFilter(String field, @Nullable Object value, Object[] params, Map<String, Object> namedParameters) {
        if (!(value instanceof Map<?, ?> m)) {
            return Filter.ALL;
        }
        NitriteSpatialSupport support = requireSupport("near");
        Object center = entityMapper.toNitriteFilterValue(
            valueResolver.preConvertForFilter(valueResolver.resolveValue(m.get("center"), params, namedParameters)));
        Object distanceObj = valueResolver.resolveValue(m.get("distance"), params, namedParameters);
        double distance = distanceObj instanceof Number n ? n.doubleValue() : 0.0;
        return support.near(field, center, distance);
    }

    Filter createSpatialFilter(String field, @Nullable Object geometry, String method) {
        if (geometry == null) {
            return Filter.ALL;
        }
        return requireSupport(method).filter(field, geometry, method);
    }

    private NitriteSpatialSupport requireSupport(String operation) {
        if (providers.isEmpty()) {
            throw new DataAccessException("Spatial filters require nitrite-spatial on the classpath via "
                + "micronaut-data-nitrite-spatial. Add this integration dependency to use $" + operation + ".");
        }
        if (providers.size() != 1) {
            throw new DataAccessException("Multiple Nitrite spatial providers are installed.");
        }
        return providers.getFirst();
    }
}
