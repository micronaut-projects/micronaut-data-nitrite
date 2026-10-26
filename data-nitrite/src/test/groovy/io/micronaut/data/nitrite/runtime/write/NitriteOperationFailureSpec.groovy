package io.micronaut.data.nitrite.runtime.write

import org.dizitart.no2.exceptions.NitriteIOException
import org.dizitart.no2.exceptions.NitriteSecurityException
import spock.lang.Specification

class NitriteOperationFailureSpec extends Specification {

    void "deserialization rejections point to the datasource's MVStore allowlist"() {
        given:
        def cause = new NitriteSecurityException("com.example.Value is not allowed to be deserialized")

        when:
        def failure = NitriteOperationFailure.from(cause, "PERSIST", "archive")

        then:
        failure.cause.is(cause)
        failure.message.startsWith("Error executing PERSIST: com.example.Value is not allowed to be deserialized")
        failure.message.contains("`micronaut.nitrite.archive.mvstore-allowed-classes`")
    }

    void "a deserialization rejection wrapped by Nitrite still yields the hint"() {
        given:
        def rejection = new NitriteSecurityException("com.example.Value is not allowed to be deserialized")
        def cause = new NitriteIOException("Unable to open map values", rejection)

        expect:
        NitriteOperationFailure.from(cause, "PERSIST", "default").message
            .contains("`micronaut.nitrite.default.mvstore-allowed-classes`")
    }

    void "other failures keep the plain operation message"() {
        expect:
        NitriteOperationFailure.from(cause, "DELETE", "default").message == "Error executing DELETE: " + cause.message

        where:
        cause << [
                new NitriteSecurityException("access denied"),
                new NitriteSecurityException(null),
                new IllegalStateException("is not allowed to be deserialized")
        ]
    }
}
