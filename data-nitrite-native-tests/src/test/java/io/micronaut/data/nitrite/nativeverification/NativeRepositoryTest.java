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
import io.micronaut.context.exceptions.BeanInstantiationException;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.Sort;
import io.micronaut.data.nitrite.conf.NitriteConfiguration;
import io.micronaut.data.nitrite.spi.NitriteProviders;
import io.micronaut.data.nitrite.spi.NitriteStoreProvider;
import io.micronaut.data.nitrite.transaction.NitriteTransactionOperations;
import org.dizitart.no2.Nitrite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Tests repository behavior, store selection and persistence in JVM and native processes. */
class NativeRepositoryTest {
    @TempDir
    Path directory;

    @Test
    void inMemoryRepositoryQueriesAndTransactions() {
        try (ApplicationContext context = context("IN_MEMORY", "")) {
            exerciseRepository(context);
        }
    }

    @Test
    void mvStoreRetainsRecordsAfterReopening() {
        assumeTrue(selected("mvstore"));
        verifyPersistence("MVSTORE");
    }

    @Test
    void rocksDbRetainsRecordsAfterReopening() {
        assumeTrue(selected("rocksdb"));
        verifyPersistence("ROCKSDB");
    }

    @Test
    void missingStoreProviderFailsClearly() {
        assumeTrue(!selected("mvstore"));
        try (ApplicationContext context = context("MVSTORE", directory.resolve("missing.db").toString())) {
            BeanInstantiationException failure = assertThrows(BeanInstantiationException.class,
                () -> context.getBean(Nitrite.class));
            assertTrue(failure.getMessage().contains("micronaut-data-nitrite-mvstore"));
        }
    }

    @Test
    void selectedProvidersMatchTheRuntimeClasspath() {
        var modes = NitriteProviders.load(NitriteStoreProvider.class).stream()
            .map(NitriteStoreProvider::storageMode).toList();
        assertEquals(selected("mvstore"), modes.contains(NitriteConfiguration.StorageMode.MVSTORE));
        assertEquals(selected("rocksdb"), modes.contains(NitriteConfiguration.StorageMode.ROCKSDB));
    }

    private void verifyPersistence(String mode) {
        String path = directory.resolve(mode + ".db").toString();
        try (ApplicationContext context = context(mode, path)) {
            exerciseRepository(context);
            context.getBean(Nitrite.class).commit();
        }
        try (ApplicationContext context = context(mode, path)) {
            NativeRecordRepository repository = context.getBean(NativeRecordRepository.class);
            assertEquals(2, repository.count());
            assertEquals("updated", repository.findById(1L).orElseThrow().getName());
        }
    }

    private static void exerciseRepository(ApplicationContext context) {
        NativeRecordRepository repository = context.getBean(NativeRecordRepository.class);
        repository.save(new NativeRecord(1L, "first"));
        repository.save(new NativeRecord(2L, "second"));
        assertEquals(1L, repository.findByName("first").getFirst().getId());
        assertEquals(2L, repository.findExplicitly("second").getFirst().getId());
        assertEquals(2L, repository.findAll(Pageable.from(0, 1, Sort.of(Sort.Order.desc("name"))))
            .getContent().getFirst().getId());
        repository.update(new NativeRecord(1L, "updated"));
        assertEquals("updated", repository.findById(1L).orElseThrow().getName());
        NitriteTransactionOperations transactions = context.getBean(NitriteTransactionOperations.class);
        assertThrows(IllegalStateException.class, () -> transactions.executeWrite(status -> {
            repository.save(new NativeRecord(3L, "rolled back"));
            throw new IllegalStateException("rollback");
        }));
        assertEquals(2, repository.count());
    }

    private static ApplicationContext context(String mode, String path) {
        return ApplicationContext.run(Map.of(
            "micronaut.nitrite.default.storage-mode", mode,
            "micronaut.nitrite.default.db-path", path,
            "micronaut.test.resources.enabled", false));
    }

    private static boolean selected(String backend) {
        String selected = System.getProperty("nitrite.native.backend", "all");
        return selected.equals("all") || selected.equals(backend);
    }
}
