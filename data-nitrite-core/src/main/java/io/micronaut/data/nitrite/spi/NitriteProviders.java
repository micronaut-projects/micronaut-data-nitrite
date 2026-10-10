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

import io.micronaut.core.annotation.Internal;

import java.util.List;
import java.util.ServiceLoader;

/**
 * Resolves typed extensions. Providers are discovered without reflective backend method calls.
 */
@Internal
public final class NitriteProviders {
    private NitriteProviders() {
    }

    /**
     * Loads the available implementations of an extension contract.
     * @param type extension contract
     * @param <T> extension type
     * @return loaded providers
     */
    public static <T> List<T> load(Class<T> type) {
        return ServiceLoader.load(type, type.getClassLoader()).stream()
            .map(ServiceLoader.Provider::get).toList();
    }
}
