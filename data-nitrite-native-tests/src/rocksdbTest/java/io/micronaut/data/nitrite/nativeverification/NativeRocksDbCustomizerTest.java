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
import io.micronaut.data.nitrite.rocksdb.RocksDbModuleCustomizer;
import org.dizitart.no2.Nitrite;
import org.dizitart.no2.rocksdb.RocksDBConfig;
import org.dizitart.no2.rocksdb.formatter.KryoObjectFormatter;
import org.dizitart.no2.store.events.StoreEventListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.Options;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies native options, formatters and listener customization. */
class NativeRocksDbCustomizerTest {
    @TempDir
    Path directory;

    @Test
    void customizerSuppliesNativeOptionsFormatterAndListener() {
        try (Options options = new Options().setCreateIfMissing(true).setWriteBufferSize(1024 * 1024);
             ApplicationContext context = ApplicationContext.builder(Map.of(
                 "micronaut.nitrite.default.storage-mode", "ROCKSDB",
                 "micronaut.nitrite.default.db-path", directory.resolve("custom").toString(),
                 "micronaut.test.resources.enabled", false)).build()) {
            KryoObjectFormatter formatter = new KryoObjectFormatter();
            StoreEventListener listener = event -> { };
            context.registerSingleton(RocksDbModuleCustomizer.class, (configuration, builder) ->
                builder.options(options).objectFormatter(formatter).addStoreEventListener(listener));
            context.start();
            NativeRecordRepository repository = context.getBean(NativeRecordRepository.class);
            repository.save(new NativeRecord(1L, "customized"));
            assertEquals("customized", repository.findById(1L).orElseThrow().getName());
            RocksDBConfig config = (RocksDBConfig) context.getBean(Nitrite.class).getStore().getStoreConfig();
            assertSame(options, config.options());
            assertSame(formatter, config.objectFormatter());
            assertTrue(config.eventListeners().contains(listener));
        }
    }
}
