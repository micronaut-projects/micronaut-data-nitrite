package io.micronaut.data.nitrite.runtime.query.ast

import org.dizitart.no2.collection.Document
import spock.lang.Specification

class NitriteSubstringSpec extends Specification {

    void "substring clamps oversized lengths without overflowing or splitting code points"() {
        given:
        def expression = new NitriteFilterAST.ExprValueNode.Substr(
            literal(source), literal(start), literal(length))

        expect:
        expression.evaluate(Document.createDocument(), new Object[0], [:]) == expected

        where:
        source      | start             | length            | expected
        "abcdef"    | 1                 | Integer.MAX_VALUE | "bcdef"
        "abcdef"    | 4                 | Integer.MAX_VALUE | "ef"
        "A😀BC"     | 1                 | Integer.MAX_VALUE | "😀BC"
        "A😀BC"     | 1                 | 1                 | "😀"
        "abcdef"    | Integer.MAX_VALUE | Integer.MAX_VALUE | ""
        "abcdef"    | -1                | 2                 | "ab"
        "abcdef"    | 2                 | -1                | ""
    }

    private static NitriteFilterAST.ExprValueNode literal(Object value) {
        new NitriteFilterAST.ExprValueNode.Literal(new CompiledValue.Literal(value))
    }
}
