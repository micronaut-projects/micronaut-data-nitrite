package io.micronaut.data.nitrite.storage

import io.micronaut.context.ApplicationContext
import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity
import io.micronaut.data.nitrite.annotation.NitriteRepository
import io.micronaut.data.nitrite.conf.NitriteConfiguration
import io.micronaut.data.nitrite.model.DatasourceRecord
import io.micronaut.data.nitrite.operations.NitriteRepositoryOperations
import io.micronaut.data.nitrite.repository.PrimaryDatasourceRepository
import io.micronaut.data.nitrite.repository.SecondaryDatasourceRepository
import io.micronaut.data.repository.CrudRepository
import io.micronaut.inject.qualifiers.Qualifiers
import io.micronaut.data.nitrite.rocksdb.RocksDbModuleCustomizer
import org.dizitart.no2.Nitrite
import org.dizitart.no2.collection.Document
import org.dizitart.no2.rocksdb.formatter.KryoObjectFormatter
import org.dizitart.no2.store.events.StoreEventListener
import org.rocksdb.Options
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import java.nio.file.Files

/**
 * Two datasources configured side by side, in every combination of the storage modes an
 * application can pick. Each repository names its datasource, so writes through one must not be
 * visible through the other, and a file-backed datasource must keep its documents across a
 * restart while an in-memory one must not.
 */
class NitriteMultipleDatasourceSpec extends Specification {

    @Shared
    File tempDir

    def setupSpec() {
        tempDir = Files.createTempDirectory("nitrite-multi-datasource").toFile()
    }

    def cleanupSpec() {
        tempDir?.deleteDir()
    }

    private Map<String, Object> properties(String primaryMode, String secondaryMode, String run) {
        Map<String, Object> props = [
                "micronaut.nitrite.primary.storage-mode"  : primaryMode,
                "micronaut.nitrite.secondary.storage-mode": secondaryMode
        ]
        if (primaryMode == "ROCKSDB") {
            props["micronaut.nitrite.primary.db-path"] = new File(tempDir, "$run-primary").absolutePath
        }
        if (secondaryMode == "ROCKSDB") {
            props["micronaut.nitrite.secondary.db-path"] = new File(tempDir, "$run-secondary").absolutePath
        }
        if (primaryMode == "MVSTORE") {
            props["micronaut.nitrite.primary.db-path"] = new File(tempDir, "$run-primary.db").absolutePath
        }
        if (secondaryMode == "MVSTORE") {
            props["micronaut.nitrite.secondary.db-path"] = new File(tempDir, "$run-secondary.db").absolutePath
        }
        return props
    }

    @Unroll
    void "two datasources stay isolated with primary #primaryMode and secondary #secondaryMode"() {
        given:
        def ctx = ApplicationContext.run(properties(primaryMode, secondaryMode, run))
        def primary = ctx.getBean(PrimaryDatasourceRepository)
        def secondary = ctx.getBean(SecondaryDatasourceRepository)

        when: "each datasource is written through its own repository"
        primary.save(new DatasourceRecord("in-primary"))
        secondary.save(new DatasourceRecord("in-secondary"))

        then: "neither datasource sees the other's documents"
        primary.findByLabel("in-primary")*.label == ["in-primary"]
        primary.findByLabel("in-secondary").isEmpty()
        secondary.findByLabel("in-secondary")*.label == ["in-secondary"]
        secondary.findByLabel("in-primary").isEmpty()

        and: "each repository resolves the operations bean of the datasource it names"
        ctx.getBean(NitriteRepositoryOperations, Qualifiers.byName("primary")) != null
        ctx.getBean(NitriteRepositoryOperations, Qualifiers.byName("secondary")) != null
        ctx.getBeansOfType(NitriteRepositoryOperations).size() == 2
        configurationOf(ctx, "primary").storageMode.name() == primaryMode
        configurationOf(ctx, "secondary").storageMode.name() == secondaryMode

        cleanup:
        ctx?.close()

        where:
        primaryMode | secondaryMode
        "IN_MEMORY" | "IN_MEMORY"
        "IN_MEMORY" | "ROCKSDB"
        "ROCKSDB"   | "ROCKSDB"

        run = "${primaryMode}-${secondaryMode}".toLowerCase()
    }

    @Unroll
    void "immutable record entities stay isolated and round-trip across datasources with primary #primaryMode and secondary #secondaryMode"() {
        given:
        def ctx = ApplicationContext.run(properties(primaryMode, secondaryMode, run))
        def primary = ctx.getBean(PrimaryImmutableLongRepository)
        def secondary = ctx.getBean(SecondaryImmutableLongRepository)

        when: "immutable records with assigned identities are written into each datasource"
        primary.save(new ImmutableDatasourcePerson(100L, "in-primary"))
        secondary.save(new ImmutableDatasourcePerson(200L, "in-secondary"))

        then: "neither datasource sees the other's records and each round-trips correctly"
        primary.findById(100L).present
        primary.findById(100L).get().name() == "in-primary"
        primary.findById(200L).isEmpty()

        secondary.findById(200L).present
        secondary.findById(200L).get().name() == "in-secondary"
        secondary.findById(100L).isEmpty()

        cleanup:
        ctx?.close()

        where:
        primaryMode | secondaryMode
        "IN_MEMORY" | "IN_MEMORY"
        "IN_MEMORY" | "ROCKSDB"
        "ROCKSDB"   | "ROCKSDB"
        "MVSTORE"   | "ROCKSDB"
        "MVSTORE"   | "IN_MEMORY"

        run = "immutable-${primaryMode}-${secondaryMode}".toLowerCase()
    }

    @Unroll
    void "documents survive a restart only in the file backed datasource with primary #primaryMode and secondary #secondaryMode"() {
        given:
        def props = properties(primaryMode, secondaryMode, "restart-$run")

        when: "documents are written and the context is closed"
        def first = ApplicationContext.run(props)
        first.getBean(PrimaryDatasourceRepository).save(new DatasourceRecord("survivor"))
        first.getBean(SecondaryDatasourceRepository).save(new DatasourceRecord("survivor"))
        first.close()

        and: "a new context opens the same configuration"
        def second = ApplicationContext.run(props)
        def primaryFound = second.getBean(PrimaryDatasourceRepository).findByLabel("survivor").size()
        def secondaryFound = second.getBean(SecondaryDatasourceRepository).findByLabel("survivor").size()

        then: "only a datasource backed by a file still holds the document"
        primaryFound == (primaryMode == "ROCKSDB" ? 1 : 0)
        secondaryFound == (secondaryMode == "ROCKSDB" ? 1 : 0)

        cleanup:
        second?.close()

        where:
        primaryMode | secondaryMode
        "IN_MEMORY" | "IN_MEMORY"
        "IN_MEMORY" | "ROCKSDB"
        "ROCKSDB"   | "ROCKSDB"

        run = "${primaryMode}-${secondaryMode}".toLowerCase()
    }

    void "a RocksDB customizer supplies options, a formatter and a listener for its datasource"() {
        given:
        def options = new Options().setCreateIfMissing(true).setWriteBufferSize(1024 * 1024)
        def formatter = new KryoObjectFormatter()
        StoreEventListener listener = { event -> } as StoreEventListener
        def customized = []
        def ctx = ApplicationContext.builder([
                "micronaut.nitrite.primary.storage-mode": "ROCKSDB",
                "micronaut.nitrite.primary.db-path": new File(tempDir, "custom-primary").absolutePath,
                "micronaut.nitrite.secondary.storage-mode": "ROCKSDB",
                "micronaut.nitrite.secondary.db-path": new File(tempDir, "custom-secondary").absolutePath
        ]).build()
        RocksDbModuleCustomizer customizer = { config, builder ->
            customized.add(config.name)
            if (config.name == "primary") {
                builder.options(options).objectFormatter(formatter).addStoreEventListener(listener)
            }
        } as RocksDbModuleCustomizer
        ctx.registerSingleton(RocksDbModuleCustomizer, customizer)
        ctx.start()

        when:
        def primary = ctx.getBean(Nitrite, Qualifiers.byName("primary"))
        def secondary = ctx.getBean(Nitrite, Qualifiers.byName("secondary"))
        primary.getCollection("custom").insert(Document.createDocument("body", "saved"))

        then:
        primary.getCollection("custom").find().firstOrNull().get("body") == "saved"
        primary.store.storeConfig.options().is(options)
        primary.store.storeConfig.objectFormatter().is(formatter)
        primary.store.storeConfig.eventListeners().contains(listener)
        !secondary.store.storeConfig.objectFormatter().is(formatter)
        !secondary.store.storeConfig.eventListeners().contains(listener)
        customized.toSet() == ["primary", "secondary"].toSet()

        cleanup:
        ctx?.close()
        options.close()
    }

    private static NitriteConfiguration configurationOf(ApplicationContext ctx, String name) {
        return ctx.getBean(NitriteConfiguration, Qualifiers.byName(name))
    }
}

@MappedEntity
record ImmutableDatasourcePerson(@Id Long id, String name) {}

@NitriteRepository("primary")
interface PrimaryImmutableLongRepository extends CrudRepository<ImmutableDatasourcePerson, Long> {}

@NitriteRepository("secondary")
interface SecondaryImmutableLongRepository extends CrudRepository<ImmutableDatasourcePerson, Long> {}
