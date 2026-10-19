package io.micronaut.data.nitrite.runtime.query

import io.micronaut.core.convert.ConversionService
import io.micronaut.data.model.runtime.RuntimeEntityRegistry
import io.micronaut.data.nitrite.model.Person
import io.micronaut.data.nitrite.model.query.NitriteInternalKeys
import io.micronaut.data.nitrite.model.query.NitriteQueryOperators
import io.micronaut.data.nitrite.runtime.mapping.NitriteEntityMapper
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.dizitart.no2.filters.AndFilter
import org.dizitart.no2.filters.ComparableFilter
import org.dizitart.no2.filters.GreaterEqualFilter
import org.dizitart.no2.filters.LesserThanFilter
import org.dizitart.no2.filters.RegexFilter
import spock.lang.Specification

/**
 * A pure {@code startsWith} must compile to an indexed range, not a regex scan: {@code RegexFilter}
 * has no {@code applyOnIndex}, so Nitrite's planner always walks the whole collection for it, while
 * a {@code GTE}/{@code LT} pair on the same field is a {@link ComparableFilter} the planner can seek
 * against an index instead.
 */
@MicronautTest(transactional = false)
class NitriteFilterBuilderStartsWithSpec extends Specification {

    @Inject
    RuntimeEntityRegistry runtimeEntityRegistry

    private NitriteFilterBuilder filterBuilder() {
        new NitriteFilterBuilder(new NitriteEntityMapper(ConversionService.SHARED, null, runtimeEntityRegistry))
    }

    private def buildPrefixFilter(String prefix) {
        def entity = runtimeEntityRegistry.getEntity(Person)
        def operators = [(NitriteQueryOperators.REGEX): [(NitriteInternalKeys.RANGE_PREFIX): prefix]]
        filterBuilder().buildFieldFilter(entity, "name", operators, new Object[0], [:])
    }

    void "a plain startsWith range marker builds an AND of two ComparableFilters, not a regex"() {
        given:
        def entity = runtimeEntityRegistry.getEntity(Person)
        def operators = [(NitriteQueryOperators.REGEX): [(NitriteInternalKeys.RANGE_PREFIX): "Ali"]]

        when:
        def filter = filterBuilder().buildFieldFilter(entity, "name", operators, new Object[0], [:])

        then:
        filter instanceof AndFilter
        filter.filters.size() == 2
        filter.filters.every { it instanceof ComparableFilter }
    }

    void "the upper bound increments the prefix's last character rather than appending MAX_VALUE"() {
        when:
        def filter = buildPrefixFilter("Ali")

        then: "appending MAX_VALUE would drop a value carrying the prefix, then MAX_VALUE, then more"
        filter.filters[0] instanceof GreaterEqualFilter
        filter.filters[0].value == "Ali"
        filter.filters[1] instanceof LesserThanFilter
        filter.filters[1].value == "Alj"
    }

    void "a trailing MAX_VALUE carries into the preceding character"() {
        when:
        def filter = buildPrefixFilter("Ali" + Character.MAX_VALUE + Character.MAX_VALUE)

        then:
        filter.filters[1].value == "Alj"
    }

    void "a prefix that is all MAX_VALUE needs no upper bound"() {
        when:
        def filter = buildPrefixFilter("" + Character.MAX_VALUE + Character.MAX_VALUE)

        then: "every string at or after it already carries the prefix"
        filter instanceof GreaterEqualFilter
    }

    void "an empty prefix stays a regex, so it matches strings and only strings"() {
        when:
        def filter = buildPrefixFilter("")

        then:
        filter instanceof RegexFilter
    }
}
