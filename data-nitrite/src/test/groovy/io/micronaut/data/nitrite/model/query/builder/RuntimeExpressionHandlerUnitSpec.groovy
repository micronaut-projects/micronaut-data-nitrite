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
        // startsWith only (no endsWith/ignoreCase): a lexicographic range, not a pattern.
        handler.handleRegex(false, true, false, new LiteralExpression("Ali"), queryState, null) ==
            [(NitriteInternalKeys.RANGE_PREFIX): "Ali"]

        // Any other combination still falls back to a regex, unchanged.
        handler.handleRegex(true, true, false, new LiteralExpression("Ali"), queryState, null) ==
            '(?i)^\\QAli\\E.*'
        handler.handleRegex(false, true, true, new LiteralExpression("Ali"), queryState, null) ==
            '^\\QAli\\E$'
    }

    void "handleRegex quotes regex metacharacters in literal values"() {
        given:
        def handler = new RuntimeExpressionHandler()
        def queryState = new NitriteQueryState()

        expect:
        handler.handleRegex(false, true, true, new LiteralExpression("a.b[0]"), queryState, null) == '^\\Qa.b[0]\\E$'
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
