package io.micronaut.data.nitrite.model.query.builder

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
}
