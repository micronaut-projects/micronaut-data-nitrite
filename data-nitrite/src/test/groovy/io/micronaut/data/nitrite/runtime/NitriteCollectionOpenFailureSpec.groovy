package io.micronaut.data.nitrite.runtime

import io.micronaut.data.exceptions.DataAccessException
import org.dizitart.no2.exceptions.NitriteSecurityException
import spock.lang.Specification

class NitriteCollectionOpenFailureSpec extends Specification {

    void "a deserialization rejection names the datasource's MVStore allowlist property"() {
        given:
        def failure = new NitriteSecurityException(
            "Unable to open map Book: Class com.example.Value is not allowed to be deserialized from the database")

        when:
        def result = NitriteCollectionRegistry.openFailure(failure, "Book", "archive")

        then:
        result instanceof DataAccessException
        result.cause.is(failure)
        result.message.startsWith("Unable to open Nitrite collection Book: Unable to open map Book")
        result.message.contains("`micronaut.nitrite.archive.mvstore-allowed-classes`")
    }

    void "a rejection reported only by the cause still names the allowlist property"() {
        given:
        def failure = new NitriteSecurityException("Unable to open map Book",
            new IllegalStateException("Class com.example.Value is not allowed to be deserialized"))

        expect:
        NitriteCollectionRegistry.openFailure(failure, "Book", "default").message
            .contains("`micronaut.nitrite.default.mvstore-allowed-classes`")
    }

    void "other security failures are rethrown unchanged"() {
        expect:
        NitriteCollectionRegistry.openFailure(failure, "Book", "default").is(failure)

        where:
        failure << [new NitriteSecurityException("access denied"), new NitriteSecurityException(null)]
    }
}
