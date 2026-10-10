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
package io.micronaut.data.nitrite.spi;

import io.micronaut.core.annotation.Nullable;
import org.dizitart.no2.common.module.NitriteModule;
import org.dizitart.no2.filters.Filter;

/**
 * Supplies spatial indexing and filters using backend-neutral values at the extension boundary.
 */
public interface NitriteSpatialSupport {
    /**
     * Creates a fresh spatial module for one datasource.
     * @return the spatial module
     */
    NitriteModule createModule();

    /**
     * Supplies additional JEP 290 patterns for the geometry values stored by this integration.
     * @return semicolon-separated patterns, or an empty string when none are needed
     */
    default String deserializationPatterns() {
        return "";
    }

    /**
     * Creates a proximity filter.
     * @param field indexed field
     * @param center converted geometry or coordinate
     * @param distance search distance
     * @return the filter
     */
    Filter near(String field, @Nullable Object center, double distance);

    /**
     * Creates a containment or intersection filter.
     * @param field indexed field
     * @param geometry converted geometry
     * @param operation within or intersects
     * @return the filter
     */
    Filter filter(String field, Object geometry, String operation);
}
