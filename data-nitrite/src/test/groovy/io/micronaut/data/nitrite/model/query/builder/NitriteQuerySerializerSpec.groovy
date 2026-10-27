package io.micronaut.data.nitrite.model.query.builder

import io.micronaut.data.nitrite.runtime.query.NitriteQueryParser
import spock.lang.Specification

class NitriteQuerySerializerSpec extends Specification {

    void "collections preserve nested element order and empty collection elements"() {
        expect:
        NitriteQuerySerializer.toJsonString([1, [true, null], [], [name: "O'Reilly", omitted: []]]) ==
            "[1,[true,null],[],{name:'O\\'Reilly'}]"
    }

    void "fallback values escape backslashes and quotes in their text representation"() {
        expect:
        NitriteQuerySerializer.toJsonString(new StringBuilder("C:\\O'Reilly")) == "'C:\\\\O\\'Reilly'"
    }

    void "quoted keys escape backslashes and quotes so the parser reads them back"() {
        expect:
        new NitriteQueryParser().parseJson(NitriteQuerySerializer.toJsonString([(key): 1])) == [(key): 1]

        where:
        key << ["o'name", "C:\\tmp"]
    }
}
