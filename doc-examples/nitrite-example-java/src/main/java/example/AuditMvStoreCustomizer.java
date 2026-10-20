package example;

// tag::mvstore-customizer[]
import io.micronaut.data.nitrite.conf.NitriteConfiguration;
import io.micronaut.data.nitrite.mvstore.MvStoreModuleCustomizer;
import jakarta.inject.Singleton;
import org.dizitart.no2.mvstore.MVStoreModuleBuilder;

@Singleton
class AuditMvStoreCustomizer implements MvStoreModuleCustomizer {
    @Override
    public void customize(NitriteConfiguration configuration, MVStoreModuleBuilder builder) {
        if ("audit".equals(configuration.getName())) {
            builder.autoCommitBufferSize(4096);
        }
    }
}
// end::mvstore-customizer[]
