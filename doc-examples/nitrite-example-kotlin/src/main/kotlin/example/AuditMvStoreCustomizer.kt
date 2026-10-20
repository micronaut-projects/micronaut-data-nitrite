package example

// tag::mvstore-customizer[]
import io.micronaut.data.nitrite.conf.NitriteConfiguration
import io.micronaut.data.nitrite.mvstore.MvStoreModuleCustomizer
import jakarta.inject.Singleton
import org.dizitart.no2.mvstore.MVStoreModuleBuilder

@Singleton
class AuditMvStoreCustomizer : MvStoreModuleCustomizer {
    override fun customize(configuration: NitriteConfiguration, builder: MVStoreModuleBuilder) {
        if (configuration.name == "audit") {
            builder.autoCommitBufferSize(4096)
        }
    }
}
// end::mvstore-customizer[]
