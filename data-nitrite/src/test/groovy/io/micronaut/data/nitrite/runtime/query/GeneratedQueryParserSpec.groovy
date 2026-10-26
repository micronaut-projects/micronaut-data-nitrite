package io.micronaut.data.nitrite.runtime.query

import io.micronaut.core.convert.ConversionService
import io.micronaut.data.model.runtime.RuntimeEntityRegistry
import io.micronaut.data.nitrite.model.Event
import io.micronaut.data.nitrite.runtime.mapping.NitriteEntityMapper
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.dizitart.no2.Nitrite
import org.dizitart.no2.collection.Document
import org.dizitart.no2.collection.NitriteCollection
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Unroll

/**
 * Parsing of the SQL-shaped query string that reaches Nitrite when a query was not built as a
 * Nitrite JSON filter. Every case is asserted by running the produced filter against a real
 * collection.
 */
@MicronautTest(transactional = false)
class GeneratedQueryParserSpec extends Specification {

    @Inject
    RuntimeEntityRegistry runtimeEntityRegistry

    @Shared
    @AutoCleanup
    Nitrite database = Nitrite.builder().openOrCreate()

    private NitriteCollection collection

    def setup() {
        collection = database.getCollection("event")
        collection.remove(org.dizitart.no2.filters.Filter.ALL)
        collection.insert(
                Document.createDocument("type", "alpha").put("priority", 10).put("payload", "hello world"),
                Document.createDocument("type", "beta").put("priority", 20).put("payload", "goodbye"),
                Document.createDocument("type", "gamma").put("priority", 30).put("payload", "hello again"),
                Document.createDocument("type", "alpha AND beta").put("priority", 40).put("payload", "literal"),
                Document.createDocument("type", "alpha's AND beta").put("priority", 50).put("payload", "with quote")
        )
    }

    private GeneratedQueryParser parser() {
        // Leaf predicates are resolved by the module's own filter builder, exactly as the runtime wires it.
        def filterBuilder = new NitriteFilterBuilder(
                new NitriteEntityMapper(ConversionService.SHARED, null, runtimeEntityRegistry))
        new GeneratedQueryParser(
                { entity, path, operators ->
                    filterBuilder.buildFieldFilter(entity, path, operators, new Object[0], [:])
                } as GeneratedQueryParser.LeafFilterFactory,
                { entity, predicate, params ->
                    filterBuilder.buildFilterFromJson(entity, predicate, params, [:])
                } as GeneratedQueryParser.PredicateFilterFactory)
    }

    private List<String> typesMatching(String query, Object... params) {
        def entity = runtimeEntityRegistry.getEntity(Event)
        def filter = parser().parseWhere(query, entity, params as Object[])
        collection.find(filter).toList().collect { it.get("type") as String }.sort()
    }

    /*
     * The predicate is compiled once per (entity, query) and bound per call, so what must hold is
     * that a parser reused across calls still answers per-parameter, and that a repeat of the same
     * query is not parsed again.
     */
    void "a compiled predicate binds fresh parameters on every execution"() {
        given: "one parser, reused - as the runtime reuses it for the life of a StoredQuery"
        def parser = parser()
        def entity = runtimeEntityRegistry.getEntity(Event)
        def query = "UPDATE Event SET payload = :p1 WHERE type = :p2"

        expect: "each execution binds its own parameter rather than the first one it saw"
        matches(parser, query, entity, "x", "alpha") == ["alpha"]
        matches(parser, query, entity, "x", "beta") == ["beta"]
        matches(parser, query, entity, "x", "alpha") == ["alpha"]
    }

    void "a query text is parsed once and reused"() {
        given:
        def parser = parser()
        def entity = runtimeEntityRegistry.getEntity(Event)
        def query = "UPDATE Event SET payload = :p1 WHERE priority > :p2"

        when: "the same query runs twice"
        matches(parser, query, entity, "x", 25)
        matches(parser, query, entity, "x", 25)

        then: "only one compiled predicate is held for it"
        parser.@compiledWhere.size() == 1

        when: "a different query runs on the same parser"
        matches(parser, query.replace("priority >", "priority <"), entity, "x", 25)

        then: "it is compiled separately rather than colliding with the first"
        parser.@compiledWhere.size() == 2
    }

    void "a predicate that cannot be parsed still fails on every execution"() {
        given: "a failure must not be cached as a successful compile"
        def parser = parser()
        def entity = runtimeEntityRegistry.getEntity(Event)
        def query = "UPDATE Event SET payload = :p1 WHERE priority + 1 = :p2"

        when:
        parser.parseWhere(query, entity, ["x", 1] as Object[])

        then:
        thrown(UnsupportedOperationException)

        when:
        parser.parseWhere(query, entity, ["x", 1] as Object[])

        then:
        thrown(UnsupportedOperationException)
    }

    private List<String> matches(GeneratedQueryParser parser, String query, entity, Object... params) {
        def filter = parser.parseWhere(query, entity, params as Object[])
        collection.find(filter).toList().collect { it.get("type") as String }.sort()
    }

    void "an alias qualified comparison resolves to the persisted field"() {
        expect:
        typesMatching("UPDATE Event event_ SET event_.payload = :p1 WHERE event_.type = :p2", "x", "beta") == ["beta"]
    }

    void "AND and OR keep their precedence"() {
        expect:
        typesMatching("UPDATE Event SET payload = :p1 WHERE type = :p2 OR priority > :p3", "x", "alpha", 25) ==
                ["alpha", "alpha AND beta", "alpha's AND beta", "gamma"]
        typesMatching("UPDATE Event SET payload = :p1 WHERE (type = :p2 OR type = :p3) AND priority > :p4",
                "x", "alpha", "beta", 15) == ["beta"]
    }

    void "a quoted literal containing AND is not split into operands"() {
        expect:
        typesMatching("UPDATE Event SET payload = :p1 WHERE type = 'alpha AND beta'", "x") == ["alpha AND beta"]
    }

    void "a quoted literal containing an escaped quote and AND is not split into operands"() {
        expect:
        typesMatching("UPDATE Event SET payload = :p1 WHERE type = 'alpha\\'s AND beta'", "x") == ["alpha's AND beta"]
    }

    void "a LIKE predicate matches the wildcard pattern"() {
        expect:
        typesMatching("UPDATE Event SET priority = :p1 WHERE payload LIKE :p2", 1, "hello%") == ["alpha", "gamma"]
    }

    void "a BETWEEN predicate matches the inclusive range"() {
        expect:
        typesMatching("UPDATE Event SET payload = :p1 WHERE priority BETWEEN :p2 AND :p3", "x", 20, 30) ==
                ["beta", "gamma"]
    }

    void "IN and IS NULL predicates are supported"() {
        expect:
        typesMatching("UPDATE Event SET payload = :p1 WHERE type IN (:p2)", "x", ["alpha", "gamma"]) ==
                ["alpha", "gamma"]
        typesMatching("UPDATE Event SET payload = :p1 WHERE processed IS NULL", "x").size() == 5
    }

    void "IN resolves each generated operand"() {
        expect:
        typesMatching("SELECT event_ FROM Event event_ WHERE event_.type IN (:p1,'gamma',:p2)",
                "alpha", "beta") == ["alpha", "beta", "gamma"]
    }

    void "generated predicates retain whitespace and negation support"() {
        expect:
        typesMatching("SELECT event_ FROM Event event_ WHERE event_.priority\t >=\t :p1", 30) ==
                ["alpha AND beta", "alpha's AND beta", "gamma"]
        typesMatching("SELECT event_ FROM Event event_ WHERE event_.type NOT IN ( :p1 , :p2 )",
                "alpha", "beta") == ["alpha AND beta", "alpha's AND beta", "gamma"]
        typesMatching("SELECT event_ FROM Event event_ WHERE event_.payload NOT  LIKE  :p1", "hello%") ==
                ["alpha AND beta", "alpha's AND beta", "beta"]
        typesMatching("SELECT event_ FROM Event event_ WHERE event_.priority BETWEEN :p1   AND   :p2", 20, 30) ==
                ["beta", "gamma"]
    }

    @Timeout(5)
    @Unroll("long malformed #description predicate is rejected without excessive backtracking")
    void "long malformed generated predicates are rejected without excessive backtracking"() {
        when:
        typesMatching("SELECT event_ FROM Event event_ WHERE " + predicate, 20)

        then:
        thrown(UnsupportedOperationException)

        where:
        description  | predicate
        "identifier" | "x" * 20_000 + " !"
        "IN"         | "priority IN (" + " " * 20_000 + ":p1"
        "BETWEEN"    | "priority BETWEEN :p1" + " " * 20_000 + "invalid"
        "LIKE"       | "priority LIKE" + " " * 20_000 + "?"
        "number"     | "priority = " + "1" * 20_000 + "?"
    }

    void "LIKE resolves a generated CONCAT pattern"() {
        expect:
        typesMatching("SELECT event_ FROM Event event_ WHERE event_.payload LIKE CONCAT('%',:p1,'%')",
                "hello") == ["alpha", "gamma"]
    }

    void "computed expressions use the document expression evaluator"() {
        expect:
        typesMatching("SELECT event_ FROM Event event_ WHERE LOWER(event_.type) = LOWER(:p1)",
                "ALPHA") == ["alpha"]
        typesMatching("SELECT event_ FROM Event event_ WHERE LENGTH(event_.payload) = :p1", 7) ==
                ["alpha AND beta", "beta"]
        typesMatching("SELECT event_ FROM Event event_ WHERE event_.priority = event_.priority").size() == 5
    }

    void "NOT negates a generated predicate group"() {
        expect:
        typesMatching("SELECT event_ FROM Event event_ WHERE NOT((event_.priority >= :p1 AND event_.priority <= :p2))",
                20, 40) == ["alpha", "alpha's AND beta"]
    }

    void "a NULL literal in a computed expression is rejected with its query fragment"() {
        when:
        typesMatching("SELECT event_ FROM Event event_ WHERE LOWER(event_.type) = NULL")

        then:
        def e = thrown(UnsupportedOperationException)
        e.message.contains("LOWER(event_.type) = NULL")
    }

    void "generated selections distinguish fields and counts from entity selections"() {
        expect:
        GeneratedQueryParser.parseProjectionFields(
                "SELECT event_.type AS type,event_.priority AS priority FROM Event event_") == ["type", "priority"]
        GeneratedQueryParser.parseProjectionFields("SELECT event_ FROM Event event_").empty
        GeneratedQueryParser.isCountProjection("SELECT COUNT(event_) FROM Event event_")
        !GeneratedQueryParser.isCountProjection("SELECT event_.priority FROM Event event_")
    }

    @Timeout(5)
    void "long malformed projection aliases do not backtrack through whitespace"() {
        expect:
        GeneratedQueryParser.parseProjectionFields(
                "SELECT event_.type" + " " * 20_000 + "NOT_ALIAS FROM Event event_").empty
    }

    @Unroll
    void "projection aliases retain ASCII identifier and whitespace rules for #selection"() {
        expect:
        GeneratedQueryParser.parseProjectionFields("SELECT " + selection + " FROM Event event_") == expected

        where:
        selection                              | expected
        "event_.type\tas\tlabel_1"              | ["type"]
        "event_.type AS label_1, event_.priority AS rank" | ["type", "priority"]
        "event_.type AS label.with.dot"         | []
        "event_.type AS label\u00e9"            | []
        "event_.type AS label extra"            | []
    }

    @Unroll
    void "quoted separators and nested groups preserve their predicate boundaries for #predicate"() {
        expect:
        typesMatching("SELECT event_ FROM Event event_ WHERE " + predicate, 40) == expected

        where:
        predicate                                                                 | expected
        "((type = 'alpha AND beta'))"                                              | ["alpha AND beta"]
        "(type = 'alpha''s AND beta')"                                              | ["alpha's AND beta"]
        "(type = 'alpha AND beta') AND (priority = :p1 OR priority = 30)"             | ["alpha AND beta"]
        "type IN ('alpha AND beta', 'alpha''s AND beta') AND (priority >= :p1)"       | ["alpha AND beta", "alpha's AND beta"]
    }

    @Timeout(5)
    void "malformed alias suffixes remain bounded after a long whitespace run"() {
        expect:
        GeneratedQueryParser.parseProjectionFields(
                "SELECT event_.type" + " " * 20_000 + "AS invalid.alias FROM Event event_").empty
    }

    void "an unsupported predicate is rejected with an explanatory message"() {
        when:
        typesMatching("UPDATE Event SET payload = :p1 WHERE UPPER(type) = :p2", "x", "ALPHA")

        then:
        def e = thrown(UnsupportedOperationException)
        e.message.contains("UPPER(type)")
    }

    void "assignments resolve to persisted fields"() {
        given:
        def entity = runtimeEntityRegistry.getEntity(Event)

        when:
        def assignments = parser().parseSet(
                "UPDATE Event event_ SET event_.payload = :p1, event_.priority = :p2 WHERE event_.type = :p3",
                entity, ["updated", 99, "beta"] as Object[])

        then:
        assignments == ["payload": "updated", "priority": 99]
    }

    void "generated arithmetic assignments retain their per-document operation"() {
        given:
        def entity = runtimeEntityRegistry.getEntity(Event)

        expect:
        parser().parseUpdate(
                "UPDATE Event event_ SET event_.priority=(event_.priority + :p1),event_.payload=:p2",
                entity, [5, "updated"] as Object[]) == ["\$inc": [priority: 5], "\$set": [payload: "updated"]]
    }

    void "generated arithmetic assignments trim the enclosing parentheses and operand whitespace"() {
        expect:
        parser().parseUpdate("UPDATE Event SET priority = ( priority + :p1   )",
                runtimeEntityRegistry.getEntity(Event), [5] as Object[]) == ["\$inc": [priority: 5]]
    }

    @Unroll
    void "generated arithmetic #operator assignments preserve their operand wrappers"() {
        expect:
        parser().parseUpdate("UPDATE Event SET priority = (priority " + operator + " :p1)",
                runtimeEntityRegistry.getEntity(Event), [5] as Object[]) == expected

        where:
        operator | expected
        "+"      | ["\$inc": [priority: 5]]
        "-"      | ["\$inc": [priority: ["\$value": 5, "\$mn_negate": true]]]
        "*"      | ["\$mul": [priority: 5]]
        "/"      | ["\$mul": [priority: ["\$value": 5, "\$mn_reciprocate": true]]]
    }

    void "an assignment that is not a bound parameter is rejected instead of silently dropped"() {
        given:
        def entity = runtimeEntityRegistry.getEntity(Event)

        when:
        parser().parseSet("UPDATE Event SET priority = priority + 1 WHERE type = :p1",
                entity, ["alpha"] as Object[])

        then:
        def e = thrown(UnsupportedOperationException)
        e.message.contains("priority")
    }
}
