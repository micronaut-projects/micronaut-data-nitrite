package io.micronaut.data.nitrite.runtime.read

import spock.lang.Specification
import spock.lang.Unroll

import java.util.regex.Pattern

class GeneratedEqualityReferencesSpec extends Specification {

    private static final Pattern FORMER_PATTERN = Pattern.compile(
        "(?<![A-Za-z0-9_.])([A-Za-z0-9_.]++)\\s*+=\\s*+:p\\d++")

    @Unroll
    void "scan #iterationIndex finds #expected like the former regex"() {
        when:
        def references = NitriteQueryExecutor.generatedEqualityReferences(where)

        then:
        references == expected
        references == formerReferences(where)

        where:
        where                                            | expected
        "b.version = :p1"                                | ["b.version"]
        "version=:p12 AND name = :p2"                    | ["version", "name"]
        "version \t\n= \r:p3"                            | ["version"]
        "b.version = :p"                                 | []
        "b.version = :px"                                | []
        "b.version = 'x' AND age > :p1"                  | []
        "x.p1 = :p1x = :p2"                              | ["x.p1"]
        "(b.version = :p1) OR (b.id=:p2)"                | ["b.version", "b.id"]
        "b.version :p1"                                  | []
        "b.version == :p1"                               | []
        ""                                               | []
        "a" * 10_000 + "!"                               | []
    }

    private static List<String> formerReferences(String where) {
        def matcher = FORMER_PATTERN.matcher(where)
        def references = []
        while (matcher.find()) {
            references << matcher.group(1)
        }
        references
    }
}
