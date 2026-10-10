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
package io.micronaut.data.nitrite.runtime;

import io.micronaut.context.BeanLocator;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.EachBean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.util.StringUtils;
import io.micronaut.data.model.runtime.AttributeConverterRegistry;
import io.micronaut.data.model.runtime.RuntimeEntityRegistry;
import io.micronaut.data.nitrite.conf.NitriteConfiguration;
import io.micronaut.data.nitrite.operations.NitriteIndexManagement;
import io.micronaut.data.nitrite.spi.NitriteProviders;
import io.micronaut.data.nitrite.spi.NitriteSpatialSupport;
import io.micronaut.data.nitrite.spi.NitriteStoreProvider;

import java.util.List;
import io.micronaut.data.nitrite.transaction.DefaultNitriteTransactionOperations;
import io.micronaut.data.nitrite.transaction.NitriteConnectionOperations;
import io.micronaut.data.nitrite.transaction.NitriteTransactionHolder;
import io.micronaut.data.runtime.convert.DataConversionService;
import io.micronaut.data.runtime.date.DateTimeProvider;
import io.micronaut.serde.ObjectMapper;
import org.dizitart.no2.Nitrite;
import org.dizitart.no2.NitriteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;

/**
 * Factory for Nitrite repository operations.
 *
 * @since 5.2.0
 */
@Factory
@Internal
public final class NitriteOperationsFactory {

  private static final Logger LOG = LoggerFactory.getLogger(NitriteOperationsFactory.class);

  NitriteOperationsFactory() {
  }

  /**
   * Create a Nitrite database instance.
   *
   * <p>No {@code NitriteMapper} module is loaded explicitly; Nitrite falls back to its built-in
   * {@code SimpleNitriteMapper}. This module only calls Nitrite's {@code NitriteCollection} API
   * (not {@code ObjectRepository}), and {@code SimpleNitriteMapper.tryConvert} passes through any
   * value that already implements the requested target type — true for the {@link Comparable}
   * check performed by filter value normalization (see {@code FieldBasedFilter}) for common types
   * like {@link java.time.Instant}, so no Jackson-based mapper is required.
   *
   * @param config the configuration
   * @return the Nitrite database instance
   */
  @Bean(preDestroy = "close")
  @EachBean(NitriteConfiguration.class)
  public Nitrite nitrite(@Parameter @NonNull final NitriteConfiguration config) {
    NitriteBuilder builder = Nitrite.builder();

    NitriteConfiguration.StorageMode mode = config.getStorageMode();
    String dbPath = config.getDbPath();

    String dbPathProperty = NitriteConfiguration.PREFIX + "." + config.getName() + ".db-path";
    if (mode == NitriteConfiguration.StorageMode.IN_MEMORY) {
      LOG.info("Nitrite configured for pure in-memory storage.");
    } else if (StringUtils.isEmpty(dbPath)) {
      if (mode == NitriteConfiguration.StorageMode.ROCKSDB) {
        throw new IllegalStateException("RocksDB storage mode requires a valid " + dbPathProperty + ".");
      }
      LOG.info("No {} provided, falling back to Nitrite pure in-memory storage.", dbPathProperty);
    } else {
      File file = prepareDbFile(dbPath);
      List<NitriteStoreProvider> providers = NitriteProviders.load(NitriteStoreProvider.class).stream()
          .filter(provider -> provider.storageMode() == mode).toList();
      if (providers.size() != 1) {
        String artifact = mode == NitriteConfiguration.StorageMode.ROCKSDB
            ? "micronaut-data-nitrite-rocksdb (org.dizitart.no2.rocksdb.RocksDBModule)"
            : "micronaut-data-nitrite-mvstore";
        throw new IllegalStateException("Storage mode " + mode + " requires exactly one provider from "
            + artifact + "; found " + providers.size() + ".");
      }
      builder.loadModule(providers.getFirst().createModule(config, file));
    }

    List<NitriteSpatialSupport> spatial = NitriteProviders.load(NitriteSpatialSupport.class);
    if (spatial.size() > 1) {
      throw new IllegalStateException("Multiple Nitrite spatial providers are installed.");
    }
    spatial.forEach(provider -> builder.loadModule(provider.createModule()));

    builder.fieldSeparator(config.getFieldSeparator());

    String username = config.getUsername();
    String password = config.getPassword();
    if (username != null && password != null) {
      return builder.openOrCreate(username, password);
    }
    return builder.openOrCreate();
  }

  private File prepareDbFile(String dbPath) {
    File file = Path.of(dbPath).toFile();
    File parent = file.getParentFile();
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
      throw new RuntimeException("Could not create directory " + parent);
    }
    return file;
  }

  /**
   * Creates repository operations for a named Nitrite datasource.
   *
   * <p>The database is looked up by the datasource name rather than injected directly: both beans
   * are created per {@link NitriteConfiguration}, so with more than one datasource configured an
   * unqualified {@link Nitrite} parameter matches every database bean at once.
   *
   * @param beanLocator the bean locator used to resolve the matching database
   * @param configuration the matching datasource configuration
   * @param dateTimeProvider the date time provider
   * @param runtimeEntityRegistry the runtime entity registry
   * @param conversionService the conversion service
   * @param attributeConverterRegistry the attribute converter registry
   * @param serdeObjectMapper the optional Serde object mapper
   * @return the repository operations
   */
  @EachBean(NitriteConfiguration.class)
  public DefaultNitriteRepositoryOperations nitriteRepositoryOperations(
      BeanLocator beanLocator,
      @Parameter NitriteConfiguration configuration,
      DateTimeProvider<Object> dateTimeProvider,
      RuntimeEntityRegistry runtimeEntityRegistry,
      DataConversionService conversionService,
      AttributeConverterRegistry attributeConverterRegistry,
      @Nullable ObjectMapper serdeObjectMapper) {
    return new DefaultNitriteRepositoryOperations(
        forDatasource(beanLocator, Nitrite.class, configuration),
        configuration,
        dateTimeProvider,
        runtimeEntityRegistry,
        conversionService,
        attributeConverterRegistry,
        forDatasource(beanLocator, NitriteTransactionHolder.class, configuration),
        serdeObjectMapper);
  }

  /**
   * Creates the transaction context holder of one datasource.
   *
   * <p>The holder is per datasource: a transaction opened against one datasource must not be
   * visible to the repositories of another.
   *
   * @param configuration the matching datasource configuration
   * @return the transaction holder of that datasource
   */
  @EachBean(NitriteConfiguration.class)
  public NitriteTransactionHolder nitriteTransactionHolder(@Parameter NitriteConfiguration configuration) {
    return new NitriteTransactionHolder();
  }

  /**
   * Creates the connection operations of one datasource.
   *
   * @param beanLocator the bean locator used to resolve the matching database
   * @param configuration the matching datasource configuration
   * @return the connection operations of that datasource
   */
  @EachBean(NitriteConfiguration.class)
  public NitriteConnectionOperations nitriteConnectionOperations(
      BeanLocator beanLocator,
      @Parameter NitriteConfiguration configuration) {
    return new NitriteConnectionOperations(forDatasource(beanLocator, Nitrite.class, configuration));
  }

  /**
   * Creates index management bound to one Nitrite datasource.
   *
   * @param beanLocator the bean locator used to resolve the matching database
   * @param configuration the matching datasource configuration
   * @return index management for that datasource
   */
  @EachBean(NitriteConfiguration.class)
  public NitriteIndexManagement nitriteIndexManagement(
      BeanLocator beanLocator,
      @Parameter NitriteConfiguration configuration) {
    return new NitriteIndexManagement(forDatasource(beanLocator, Nitrite.class, configuration));
  }

  /**
   * Creates the transaction operations of one datasource, over that datasource's own connection
   * operations and transaction holder.
   *
   * @param beanLocator the bean locator used to resolve the matching beans
   * @param configuration the matching datasource configuration
   * @return the transaction operations of that datasource
   */
  @EachBean(NitriteConfiguration.class)
  public DefaultNitriteTransactionOperations nitriteTransactionOperations(
      BeanLocator beanLocator,
      @Parameter NitriteConfiguration configuration) {
    NitriteConnectionOperations connectionOperations =
        forDatasource(beanLocator, NitriteConnectionOperations.class, configuration);
    return new DefaultNitriteTransactionOperations(
        connectionOperations,
        connectionOperations,
        forDatasource(beanLocator, NitriteTransactionHolder.class, configuration));
  }

  /**
   * Resolves the instance of a per-datasource bean that belongs to one configuration.
   *
   * <p>The implicit default configuration is a primary bean rather than a named one, so it has no
   * name qualifier to match on and its beans are resolved as the primary ones instead.
   *
   * @param beanLocator the bean locator
   * @param beanType the per-datasource bean type
   * @param configuration the matching datasource configuration
   * @param <T> the per-datasource bean type
   * @return the instance belonging to that datasource
   */
  private <T> T forDatasource(BeanLocator beanLocator, Class<T> beanType, NitriteConfiguration configuration) {
    // Only the primary (unnamed) configuration falls back to the unqualified bean.
    // Secondary datasources must fail-fast if their specific bean is missing, otherwise
    // they would quietly bind to the primary datasource's instances.
    if (NitriteConfiguration.DEFAULT_NAME.equals(configuration.getName())) {
      return beanLocator.findBean(beanType, Qualifiers.byName(configuration.getName()))
          .orElseGet(() -> beanLocator.getBean(beanType));
    }
    return beanLocator.getBean(beanType, Qualifiers.byName(configuration.getName()));
  }

}
