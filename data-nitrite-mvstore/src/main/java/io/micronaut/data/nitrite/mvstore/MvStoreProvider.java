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

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.nitrite.conf.NitriteConfiguration;
import io.micronaut.data.nitrite.spi.NitriteStoreProvider;
import org.dizitart.no2.common.module.NitriteModule;
import org.dizitart.no2.mvstore.MVStoreModule;
import org.dizitart.no2.mvstore.MVStoreModuleBuilder;

import java.io.File;
import java.util.function.Consumer;

/**
 * MVStore integration retaining the datasource's nullable adapter settings.
 */
public final class MvStoreProvider implements NitriteStoreProvider {
  /** Creates a provider. */
  public MvStoreProvider() {
  }

  @Override
  public NitriteConfiguration.StorageMode storageMode() {
    return NitriteConfiguration.StorageMode.MVSTORE;
  }

  @Override
  public NitriteModule createModule(NitriteConfiguration config, File file) {
    MVStoreModuleBuilder mvStore = MVStoreModule.withConfig().filePath(file);
    applyIfSet(config.getMvstorePageSplitSize(), mvStore::pageSplitSize);
    applyIfSet(config.getMvstoreCacheSize(), mvStore::cacheSize);
    applyIfSet(config.getMvstoreCacheConcurrency(), mvStore::cacheConcurrency);
    applyIfSet(config.getMvstoreAutoCommitBufferSize(), mvStore::autoCommitBufferSize);
    applyIfSet(config.getMvstoreAutoCommit(), mvStore::autoCommit);
    applyIfSet(config.getMvstoreAutoCompact(), mvStore::autoCompact);
    applyIfSet(config.getMvstoreCompress(), mvStore::compress);
    applyIfSet(config.getMvstoreCompressHigh(), mvStore::compressHigh);
    applyIfSet(config.getMvstoreRecoveryMode(), mvStore::recoveryMode);
    applyIfSet(config.getMvstoreReadOnly(), mvStore::readOnly);
    String encryptionKey = config.getMvstoreEncryptionKey();
    if (encryptionKey != null) {
      mvStore.encryptionKey(encryptionKey.toCharArray());
    }
    return mvStore.build();
  }

  private static <V> void applyIfSet(@Nullable final V value, final Consumer<V> setter) {
    if (value != null) {
      setter.accept(value);
    }
  }

}
