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

import io.micronaut.context.BeanLocator;
import io.micronaut.data.nitrite.conf.NitriteConfiguration;
import org.dizitart.no2.common.module.NitriteModule;

import java.io.File;

/**
 * Creates a storage module without coupling repository support to a backend library.
 * @since 5.2.0
 */
public interface NitriteStoreProvider {
    /**
     * Returns the storage mode supplied by this provider.
     * @return the supported mode
     */
    NitriteConfiguration.StorageMode storageMode();

    /**
     * Creates a fresh module for one datasource.
     * @param configuration datasource settings
     * @param file prepared database path
     * @return the configured module
     */
    NitriteModule createModule(NitriteConfiguration configuration, File file);

    /**
     * Creates a module with application-provided builder customization.
     * Existing providers retain their two-argument implementation by default.
     * @param configuration datasource settings
     * @param file prepared database path
     * @param beanLocator application beans
     * @return the configured module
     * @since 5.2.0
     */
    default NitriteModule createModule(NitriteConfiguration configuration, File file, BeanLocator beanLocator) {
        return createModule(configuration, file);
    }
}
