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
package io.micronaut.data.nitrite.nativeverification;

import io.micronaut.context.ApplicationContext;
import io.micronaut.data.nitrite.mvstore.MvStoreModuleCustomizer;
import org.dizitart.no2.Nitrite;
import org.dizitart.no2.mvstore.MVStoreConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies application builder customization in JVM and native processes. */
class NativeMvStoreCustomizerTest {
    @TempDir
    Path directory;

    @Test
    void customizerOverridesPropertiesBeforeTheStoreOpens() {
        try (ApplicationContext context = ApplicationContext.builder(Map.of(
            "micronaut.nitrite.default.db-path", directory.resolve("custom.db").toString(),
            "micronaut.nitrite.default.mvstore-cache-size", 32,
            "micronaut.test.resources.enabled", false)).build()) {
            context.registerSingleton(MvStoreModuleCustomizer.class, (configuration, builder) ->
                builder.cacheSize(64).retentionTime(1000).versionsToKeep(3));
            context.start();
            NativeRecordRepository repository = context.getBean(NativeRecordRepository.class);
            repository.save(new NativeRecord(1L, "customized"));
            assertEquals("customized", repository.findById(1L).orElseThrow().getName());
            MVStoreConfig config = (MVStoreConfig) context.getBean(Nitrite.class).getStore().getStoreConfig();
            assertEquals(64, config.cacheSize());
            assertEquals(1000, config.retentionTime());
            assertEquals(3, config.versionsToKeep());
        }
    }
}
