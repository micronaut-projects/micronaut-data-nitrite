package io.micronaut.data.nitrite.model.query.builder

import io.micronaut.data.model.jpa.criteria.impl.expression.LiteralExpression
import io.micronaut.data.nitrite.model.query.NitriteInternalKeys
import spock.lang.Specification

class RuntimeExpressionHandlerUnitSpec extends Specification {

    void "handleRegex builds an indexed prefix range instead of a regex for a plain startsWith"() {
        given:
        def handler = new RuntimeExpressionHandler()
        def queryState = new NitriteQueryState()

        expect:
        // startsWith only (no endsWith/ignoreCase/isLike): a lexicographic range, not a pattern.
        handler.handleRegex("name", false, false, true, false, new LiteralExpression("Ali"), false, null, queryState, null) ==
            [(NitriteInternalKeys.RANGE_PREFIX): "Ali"]

        // Any other combination still falls back to a regex, unchanged.
        handler.handleRegex("name", true, false, true, false, new LiteralExpression("Ali"), false, null, queryState, null) ==
            '(?i)^\\QAli\\E.*'
        handler.handleRegex("name", false, false, true, true, new LiteralExpression("Ali"), false, null, queryState, null) ==
            '^\\QAli\\E$'
    }

    void "handleRegex unwraps literal expressions for runtime LIKE"() {
        given:
        def handler = new RuntimeExpressionHandler()
        def queryState = new NitriteQueryState()

        expect:
        handler.handleRegex("hexadecimal", false, false, false, false, new LiteralExpression("4_"), true, null, queryState, null) == '(?s)^4.$'
        handler.handleRegex("hexadecimal", false, false, false, false, new LiteralExpression(new LiteralExpression("4_")), true, null, queryState, null) == '(?s)^4.$'
        handler.handleRegex("hexadecimal", false, false, false, false, new LiteralExpression("4\\_"), true, new LiteralExpression('\\' as char), queryState, null) == '(?s)^4_$'
        handler.handleRegex("hexadecimal", false, false, false, false, new LiteralExpression("4\\_"), true, new LiteralExpression("\\"), queryState, null) == '(?s)^4_$'
    }

    void "handleRegex quotes regex metacharacters in literal values"() {
        given:
        def handler = new RuntimeExpressionHandler()
        def queryState = new NitriteQueryState()

        expect:
        handler.handleRegex("field", false, false, true, true, new LiteralExpression("a.b[0]"), false, null, queryState, null) == '^\\Qa.b[0]\\E$'
    }

    void "resolveCollectionValue unwraps literal iterable expressions"() {
        given:
        def handler = new RuntimeExpressionHandler()
        def queryState = new NitriteQueryState()

        expect:
        handler.resolveCollectionValue(queryState, null, new LiteralExpression(["E", "G"])) == ["E", "G"]
        handler.resolveCollectionValue(queryState, null, new LiteralExpression(new LiteralExpression(["E", "G"]))) == ["E", "G"]
    }
}
