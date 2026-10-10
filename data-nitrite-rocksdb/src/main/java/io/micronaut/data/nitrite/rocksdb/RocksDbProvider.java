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
package io.micronaut.data.nitrite.rocksdb;

import io.micronaut.context.BeanLocator;
import io.micronaut.core.annotation.Internal;
import io.micronaut.data.nitrite.conf.NitriteConfiguration;
import io.micronaut.data.nitrite.spi.NitriteStoreProvider;
import org.dizitart.no2.common.module.NitriteModule;
import org.dizitart.no2.rocksdb.RocksDBModule;
import org.dizitart.no2.rocksdb.RocksDBModuleBuilder;

import java.io.File;
import java.util.Comparator;

/**
 * RocksDB integration for a persistent datasource.
 * @since 5.2.0
 */
@Internal
public final class RocksDbProvider implements NitriteStoreProvider {
    /** Creates a provider. */
    public RocksDbProvider() {
    }

    @Override
    public NitriteConfiguration.StorageMode storageMode() {
        return NitriteConfiguration.StorageMode.ROCKSDB;
    }

    @Override
    public NitriteModule createModule(NitriteConfiguration configuration, File file) {
        return RocksDBModule.withConfig().filePath(file).build();
    }

    @Override
    public NitriteModule createModule(NitriteConfiguration configuration, File file, BeanLocator beanLocator) {
        RocksDBModuleBuilder builder = RocksDBModule.withConfig().filePath(file);
        beanLocator.getBeansOfType(RocksDbModuleCustomizer.class).stream()
            .sorted(Comparator.comparingInt(RocksDbModuleCustomizer::getOrder))
            .forEach(customizer -> customizer.customize(configuration, builder));
        return builder.build();
    }
}
