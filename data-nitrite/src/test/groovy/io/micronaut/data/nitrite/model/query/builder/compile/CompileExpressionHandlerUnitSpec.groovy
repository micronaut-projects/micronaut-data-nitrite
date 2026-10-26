package io.micronaut.data.nitrite.model.query.builder.compile

import io.micronaut.data.model.jpa.criteria.impl.expression.LiteralExpression
import io.micronaut.data.nitrite.model.query.NitriteInternalKeys
import io.micronaut.data.nitrite.model.query.builder.NitriteQueryState
import spock.lang.Specification

class CompileExpressionHandlerUnitSpec extends Specification {

    void "test resolveValue with literal"() {
        given:
        def handler = new CompileExpressionHandler()
        def queryState = new NitriteQueryState(null)

        expect:
        handler.resolveValue(queryState, null, new LiteralExpression("test")) == "test"
        handler.resolveValue(queryState, null, new LiteralExpression(new LiteralExpression("test"))) == "test"
        handler.resolveValue(queryState, null, new LiteralExpression(new RegexPattern(".*"))) == ".*"

        // fallback to RuntimeExpressionHandler for non-literals
        handler.resolveValue(queryState, null, "not-literal") == "not-literal"
    }

    void "test handleRegex with literal"() {
        given:
        def handler = new CompileExpressionHandler()
        def queryState = new NitriteQueryState(null)
        def literal = new LiteralExpression("John")

        expect:
        // startsWith = true, plain: an indexed prefix range, not a regex.
        handler.handleRegex(false, true, false, literal, queryState, null) ==
            [(NitriteInternalKeys.RANGE_PREFIX): "John"]

        // endsWith = true
        handler.handleRegex(false, false, true, literal, queryState, null) == '.*\\QJohn\\E$'

        // contains
        handler.handleRegex(false, false, false, literal, queryState, null) == '.*\\QJohn\\E.*'

        // ignoreCase = true
        handler.handleRegex(true, false, false, literal, queryState, null) == '(?i).*\\QJohn\\E.*'
    }

    void "test resolveRegexValue"() {
        given:
        def handler = new CompileExpressionHandler()
        def queryState = new NitriteQueryState(null)

        expect:
        handler.resolveRegexValue(queryState, null, new LiteralExpression("pattern")) == "pattern"
        handler.resolveRegexValue(queryState, null, new LiteralExpression(new LiteralExpression("pattern"))) == "pattern"

        // fallback to resolveValue for non-string literals
        handler.resolveRegexValue(queryState, null, new LiteralExpression(123)) == 123
    }

    void "test resolveCollectionValue"() {
        given:
        def handler = new CompileExpressionHandler()
        def queryState = new NitriteQueryState(null)

        expect:
        // Iterable inside literal
        handler.resolveCollectionValue(queryState, null, new LiteralExpression(["a", "b"])) == ["a", "b"]
    }
}
