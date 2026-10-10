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
package io.micronaut.data.nitrite.conf;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.naming.Named;

/**
 * Configuration properties for a named NitriteDB datasource.
 *
 * <p>Example:
 *
 * <pre>
 * micronaut:
 *   nitrite:
 *     default:
 *       db-path: /data/myapp.db
 *       storage-mode: MVSTORE
 *       create-indexes: true
 *       sorted-read-strategy: AUTO
 *       username: admin
 *       password: secret
 * </pre>
 */
public class NitriteConfiguration implements Named {

  /** Configuration prefix used for binding named Nitrite settings. */
  public static final String PREFIX = "micronaut.nitrite";

  /**
   * Name of the implicit datasource. It is the primary configuration, so its per-datasource beans
   * are resolvable without a name qualifier; every other datasource is named and qualified.
   */
  public static final String DEFAULT_NAME = "default";

  private final String name;

  /**
   * Path to the NitriteDB file. If not provided and storage-mode is MVSTORE,
   * an in-memory database is created.
   */
  @Nullable private String dbPath;

  /**
   * The storage mode to use. Defaults to {@code MVSTORE}.
   */
  private StorageMode storageMode = StorageMode.MVSTORE;

  /**
   * Strategy for applying limits to sorted reads. Defaults to {@code AUTO}, which selects the
   * strategy measured to be best for the configured storage mode.
   */
  private SortedReadStrategy sortedReadStrategy = SortedReadStrategy.AUTO;

  /**
   * The field separator character used for nested properties. Defaults to {@code "."}.
   */
  private String fieldSeparator = ".";

  /**
   * Whether to automatically create indexes based on {@code @Index} annotations and scalar
   * identity properties.
   * Defaults to {@code true}.
   */
  private boolean createIndexes = true;

  /**
   * The MVStore page split size in bytes, or {@code null} to leave the adapter's own default in
   * place. Only read when the storage mode is {@code MVSTORE}.
   *
   * <p>Nitrite 5.4.0 defaults this to 16 KB; the explicit {@code 16384} workaround for
   * Nitrite 5.3.0 is no longer needed. H2 clamps this value to
   * {@code (cacheSize / cacheConcurrency) >> 4}.
   *
   * <p>An existing file keeps the page shape it was written with until those pages are rewritten.
   */
  @Nullable private Integer mvstorePageSplitSize;

  /**
   * Additional semicolon-separated JEP 290 deserialization patterns. Nitrite and JDK classes
   * are allowed by the adapter; the spatial integration also allows its JTS geometry classes.
   * A process-wide serial filter can still reject a class. {@code null} adds no application types.
   */
  @Nullable private String mvstoreAllowedClasses;

  /**
   * Chunk retention time in milliseconds. {@code null} preserves H2's default of 45 seconds.
   * Setting this and versions-to-keep to zero can leave a store that will not reopen.
   */
  @Nullable private Integer mvstoreRetentionTime;

  /**
   * Number of old versions retained. {@code null} preserves H2's default of five.
   */
  @Nullable private Integer mvstoreVersionsToKeep;

  /**
   * The MVStore read cache size in MB, or {@code null} to leave the adapter's default (16).
   */
  @Nullable private Integer mvstoreCacheSize;

  /**
   * The number of segments in the MVStore read cache, or {@code null} to leave the adapter's
   * default (16). H2 clamps the page split size to {@code (cacheSize / cacheConcurrency) >> 4},
   * so this bounds {@link #getMvstorePageSplitSize()} as well.
   */
  @Nullable private Integer mvstoreCacheConcurrency;

  /**
   * Whether MVStore commits every change as it is made, or {@code null} to leave the adapter's
   * default ({@code true}). With this off, changes are buffered until {@code Nitrite.commit()}.
   */
  @Nullable private Boolean mvstoreAutoCommit;

  /**
   * The number of unsaved changes MVStore buffers before committing automatically, or
   * {@code null} to leave the adapter's default (1024). Only meaningful while auto-commit is on.
   */
  @Nullable private Integer mvstoreAutoCommitBufferSize;

  /**
   * Whether MVStore reclaims fragmented chunks and chunks below the 90% target fill rate, which
   * shrinks the file. {@code null} leaves the adapter's default ({@code true}).
   */
  @Nullable private Boolean mvstoreAutoCompact;

  /**
   * Whether MVStore compresses stored data, or {@code null} to leave the adapter's default
   * ({@code false}).
   */
  @Nullable private Boolean mvstoreCompress;

  /**
   * Whether MVStore uses its higher compression level - smaller file, slower reads and writes.
   * {@code null} leaves the adapter's default ({@code false}).
   */
  @Nullable private Boolean mvstoreCompressHigh;

  /**
   * Whether to open the store in MVStore's recovery mode, which tolerates a file H2 would
   * otherwise refuse. {@code null} leaves the adapter's default ({@code false}).
   */
  @Nullable private Boolean mvstoreRecoveryMode;

  /**
   * Whether to open the store read-only, or {@code null} to leave the adapter's default
   * ({@code false}).
   */
  @Nullable private Boolean mvstoreReadOnly;

  /**
   * The key MVStore encrypts the file with, or {@code null} for an unencrypted store. Encryption
   * is an MVStore feature; the RocksDB adapter has no equivalent, so this is ignored for any other
   * storage mode. The key is read as configured, so keep it out of checked-in configuration and
   * supply it from the environment.
   */
  @Nullable private String mvstoreEncryptionKey;

  /** Optional username for authenticated databases. */
  @Nullable private String username;

  /** Optional password for authenticated databases. */
  @Nullable private String password;

  /**
   * Default constructor.
   *
   * @param name The configuration name
   */
  public NitriteConfiguration(String name) {
    this.name = name;
  }

  @Override
  public String getName() {
    return name;
  }

  /**
   * Returns the db path.
   * @return the db path
   */
  @Nullable
  public String getDbPath() {
    return dbPath;
  }

  /**
   * Sets the db path.
   * @param dbPath the db path
   */
  public void setDbPath(@Nullable final String dbPath) {
    this.dbPath = dbPath;
  }

  /**
   * Returns the username.
   * @return the username
   */
  @Nullable
  public String getUsername() {
    return username;
  }

  /**
   * Sets the username.
   * @param username the username
   */
  public void setUsername(@Nullable final String username) {
    this.username = username;
  }

  /**
   * Returns the password.
   * @return the password
   */
  @Nullable
  public String getPassword() {
    return password;
  }

  /**
   * Sets the password.
   * @param password the password
   */
  public void setPassword(@Nullable final String password) {
    this.password = password;
  }

  /**
   * Returns the storage mode.
   * @return the storage mode
   */
  public StorageMode getStorageMode() {
    return storageMode;
  }

  /**
   * Sets the storage mode.
   * @param storageMode the storage mode
   */
  public void setStorageMode(StorageMode storageMode) {
    this.storageMode = storageMode;
  }

  /**
   * Returns the sorted-read limit strategy.
   * @return the sorted-read limit strategy
   */
  public SortedReadStrategy getSortedReadStrategy() {
    return sortedReadStrategy;
  }

  /**
   * Sets the sorted-read limit strategy.
   * @param sortedReadStrategy the sorted-read limit strategy
   */
  public void setSortedReadStrategy(SortedReadStrategy sortedReadStrategy) {
    this.sortedReadStrategy = sortedReadStrategy;
  }

  /**
   * Returns the field separator.
   * @return the field separator
   */
  public String getFieldSeparator() {
    return fieldSeparator;
  }

  /**
   * Sets the field separator.
   * @param fieldSeparator the field separator
   */
  public void setFieldSeparator(String fieldSeparator) {
    this.fieldSeparator = fieldSeparator;
  }

  /**
   * Returns whether to create indexes.
   * @return whether to create indexes
   */
  public boolean isCreateIndexes() {
    return createIndexes;
  }

  /**
   * Sets whether to create indexes.
   * @param createIndexes whether to create indexes
   */
  public void setCreateIndexes(boolean createIndexes) {
    this.createIndexes = createIndexes;
  }

  /**
   * Returns the MVStore page split size in bytes.
   * @return the page split size, or {@code null} to use the adapter default
   */
  @Nullable
  public Integer getMvstorePageSplitSize() {
    return mvstorePageSplitSize;
  }

  /**
   * Sets the MVStore page split size in bytes.
   * @param mvstorePageSplitSize the page split size, or {@code null} to use the adapter default
   */
  public void setMvstorePageSplitSize(@Nullable final Integer mvstorePageSplitSize) {
    this.mvstorePageSplitSize = mvstorePageSplitSize;
  }

  /**
   * Returns additional JEP 290 patterns for stored application values.
   * @return the patterns, or {@code null} to add no application types
   */
  @Nullable
  public String getMvstoreAllowedClasses() {
    return mvstoreAllowedClasses;
  }

  /**
   * Sets additional JEP 290 patterns for stored application values.
   * @param mvstoreAllowedClasses semicolon-separated patterns, or {@code null}
   */
  public void setMvstoreAllowedClasses(@Nullable final String mvstoreAllowedClasses) {
    this.mvstoreAllowedClasses = mvstoreAllowedClasses;
  }

  /**
   * Returns the chunk retention time in milliseconds.
   * @return the retention time, or {@code null} to preserve H2's default
   */
  @Nullable
  public Integer getMvstoreRetentionTime() {
    return mvstoreRetentionTime;
  }

  /**
   * Sets the chunk retention time in milliseconds.
   * @param mvstoreRetentionTime the retention time, or {@code null} to preserve H2's default
   */
  public void setMvstoreRetentionTime(@Nullable final Integer mvstoreRetentionTime) {
    this.mvstoreRetentionTime = mvstoreRetentionTime;
  }

  /**
   * Returns the number of old versions retained.
   * @return the version count, or {@code null} to preserve H2's default
   */
  @Nullable
  public Integer getMvstoreVersionsToKeep() {
    return mvstoreVersionsToKeep;
  }

  /**
   * Sets the number of old versions retained.
   * @param mvstoreVersionsToKeep the version count, or {@code null} to preserve H2's default
   */
  public void setMvstoreVersionsToKeep(@Nullable final Integer mvstoreVersionsToKeep) {
    this.mvstoreVersionsToKeep = mvstoreVersionsToKeep;
  }

  /**
   * Returns the MVStore read cache size in MB.
   * @return the cache size, or {@code null} to use the adapter default
   */
  @Nullable
  public Integer getMvstoreCacheSize() {
    return mvstoreCacheSize;
  }

  /**
   * Sets the MVStore read cache size in MB.
   * @param mvstoreCacheSize the cache size, or {@code null} to use the adapter default
   */
  public void setMvstoreCacheSize(@Nullable final Integer mvstoreCacheSize) {
    this.mvstoreCacheSize = mvstoreCacheSize;
  }

  /**
   * Returns the MVStore read cache concurrency.
   * @return the number of cache segments, or {@code null} to use the adapter default
   */
  @Nullable
  public Integer getMvstoreCacheConcurrency() {
    return mvstoreCacheConcurrency;
  }

  /**
   * Sets the MVStore read cache concurrency.
   * @param mvstoreCacheConcurrency the number of cache segments, or {@code null} to use the adapter default
   */
  public void setMvstoreCacheConcurrency(@Nullable final Integer mvstoreCacheConcurrency) {
    this.mvstoreCacheConcurrency = mvstoreCacheConcurrency;
  }

  /**
   * Returns whether MVStore auto-commits.
   * @return whether auto-commit is enabled, or {@code null} to use the adapter default
   */
  @Nullable
  public Boolean getMvstoreAutoCommit() {
    return mvstoreAutoCommit;
  }

  /**
   * Sets whether MVStore auto-commits.
   * @param mvstoreAutoCommit whether auto-commit is enabled, or {@code null} to use the adapter default
   */
  public void setMvstoreAutoCommit(@Nullable final Boolean mvstoreAutoCommit) {
    this.mvstoreAutoCommit = mvstoreAutoCommit;
  }

  /**
   * Returns the MVStore auto-commit buffer size.
   * @return the buffer size, or {@code null} to use the adapter default
   */
  @Nullable
  public Integer getMvstoreAutoCommitBufferSize() {
    return mvstoreAutoCommitBufferSize;
  }

  /**
   * Sets the MVStore auto-commit buffer size.
   * @param mvstoreAutoCommitBufferSize the buffer size, or {@code null} to use the adapter default
   */
  public void setMvstoreAutoCommitBufferSize(@Nullable final Integer mvstoreAutoCommitBufferSize) {
    this.mvstoreAutoCommitBufferSize = mvstoreAutoCommitBufferSize;
  }

  /**
   * Returns whether MVStore auto-compacts.
   * @return whether auto-compact is enabled, or {@code null} to use the adapter default
   */
  @Nullable
  public Boolean getMvstoreAutoCompact() {
    return mvstoreAutoCompact;
  }

  /**
   * Sets whether MVStore auto-compacts.
   * @param mvstoreAutoCompact whether auto-compact is enabled, or {@code null} to use the adapter default
   */
  public void setMvstoreAutoCompact(@Nullable final Boolean mvstoreAutoCompact) {
    this.mvstoreAutoCompact = mvstoreAutoCompact;
  }

  /**
   * Returns whether MVStore compresses stored data.
   * @return whether compression is enabled, or {@code null} to use the adapter default
   */
  @Nullable
  public Boolean getMvstoreCompress() {
    return mvstoreCompress;
  }

  /**
   * Sets whether MVStore compresses stored data.
   * @param mvstoreCompress whether compression is enabled, or {@code null} to use the adapter default
   */
  public void setMvstoreCompress(@Nullable final Boolean mvstoreCompress) {
    this.mvstoreCompress = mvstoreCompress;
  }

  /**
   * Returns whether MVStore uses high compression.
   * @return whether high compression is enabled, or {@code null} to use the adapter default
   */
  @Nullable
  public Boolean getMvstoreCompressHigh() {
    return mvstoreCompressHigh;
  }

  /**
   * Sets whether MVStore uses high compression.
   * @param mvstoreCompressHigh whether high compression is enabled, or {@code null} to use the adapter default
   */
  public void setMvstoreCompressHigh(@Nullable final Boolean mvstoreCompressHigh) {
    this.mvstoreCompressHigh = mvstoreCompressHigh;
  }

  /**
   * Returns whether the store opens in MVStore's recovery mode.
   * @return whether recovery mode is enabled, or {@code null} to use the adapter default
   */
  @Nullable
  public Boolean getMvstoreRecoveryMode() {
    return mvstoreRecoveryMode;
  }

  /**
   * Sets whether the store opens in MVStore's recovery mode.
   * @param mvstoreRecoveryMode whether recovery mode is enabled, or {@code null} to use the adapter default
   */
  public void setMvstoreRecoveryMode(@Nullable final Boolean mvstoreRecoveryMode) {
    this.mvstoreRecoveryMode = mvstoreRecoveryMode;
  }

  /**
   * Returns whether the store opens read-only.
   * @return whether read-only is enabled, or {@code null} to use the adapter default
   */
  @Nullable
  public Boolean getMvstoreReadOnly() {
    return mvstoreReadOnly;
  }

  /**
   * Sets whether the store opens read-only.
   * @param mvstoreReadOnly whether read-only is enabled, or {@code null} to use the adapter default
   */
  public void setMvstoreReadOnly(@Nullable final Boolean mvstoreReadOnly) {
    this.mvstoreReadOnly = mvstoreReadOnly;
  }

  /**
   * Returns the MVStore encryption key.
   * @return the encryption key, or {@code null} for an unencrypted store
   */
  @Nullable
  public String getMvstoreEncryptionKey() {
    return mvstoreEncryptionKey;
  }

  /**
   * Sets the MVStore encryption key.
   * @param mvstoreEncryptionKey the encryption key, or {@code null} for an unencrypted store
   */
  public void setMvstoreEncryptionKey(@Nullable final String mvstoreEncryptionKey) {
    this.mvstoreEncryptionKey = mvstoreEncryptionKey;
  }

  /**
   * Storage modes supported by NitriteDB.
   */
  public enum StorageMode {
    /**
     * MVStore-backed persistent database.
     */
    MVSTORE,
    /**
     * In-memory database.
     */
    IN_MEMORY,
    /**
     * RocksDB-backed persistent database.
     */
    ROCKSDB
  }

  /**
   * Controls whether sorted row limits are applied by Nitrite or by the returned cursor.
   */
  public enum SortedReadStrategy {
    /** Select the measured strategy for the configured storage mode. */
    AUTO,
    /** Remove the limit from the Nitrite find and apply it while reading the cursor. */
    CURSOR,
    /** Keep the limit on the Nitrite find. */
    DATABASE;

    /**
     * Determines whether the sorted limit should be moved to the cursor.
     *
     * @param storageMode the configured storage mode
     * @return true when the cursor should apply the limit
     */
    public boolean usesCursorLimit(StorageMode storageMode) {
      return switch (this) {
        case AUTO -> storageMode != StorageMode.ROCKSDB;
        case CURSOR -> true;
        case DATABASE -> false;
      };
    }
  }
}
