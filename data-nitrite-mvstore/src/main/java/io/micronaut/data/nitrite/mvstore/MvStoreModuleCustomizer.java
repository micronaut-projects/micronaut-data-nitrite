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
package io.micronaut.data.nitrite.mvstore;

import io.micronaut.core.order.Ordered;
import io.micronaut.data.nitrite.conf.NitriteConfiguration;
import org.dizitart.no2.mvstore.MVStoreModuleBuilder;

/**
 * Customizes a fresh backend builder after datasource properties have been applied.
 * Register implementations as Micronaut beans. Each bean receives the datasource configuration
 * and can select the datasource by name. Lower orders run first; later customizers can override
 * earlier settings. Resources supplied to the builder must outlive the database using them.
 */
@FunctionalInterface
public interface MvStoreModuleCustomizer extends Ordered {
    /**
     * Customizes the builder before its module is built.
     * @param configuration datasource settings
     * @param builder fresh backend builder
     */
    void customize(NitriteConfiguration configuration, MVStoreModuleBuilder builder);
}
