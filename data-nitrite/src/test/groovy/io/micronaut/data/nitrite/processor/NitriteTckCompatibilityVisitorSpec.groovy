package io.micronaut.data.nitrite.processor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.data.annotation.Transient
import io.micronaut.data.nitrite.annotation.NitriteRepository
import io.micronaut.inject.ast.ClassElement
import spock.lang.Unroll

import java.nio.charset.StandardCharsets

class NitriteTckCompatibilityVisitorSpec extends AbstractTypeElementSpec {

    private static final String REPOSITORY = '''
package test;

import io.micronaut.data.annotation.Repository;

@Repository
interface Products {
}
'''

    private static final String ENTITY = '''
package test;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import java.util.List;

@Entity
class Product {
    String name;
    @ElementCollection
    List<String> tags;
}
'''

    @Unroll
    void "the TCK marker '#marker' selects Nitrite: #expected"() {
        expect:
        NitriteTckCompatibilityVisitor.isNitrite(marker == null ? null : new ByteArrayInputStream(marker.getBytes(StandardCharsets.ISO_8859_1))) == expected

        where:
        marker                      | expected
        null                        | false
        'implementation=nitrite'    | true
        'implementation=mongo'      | false
        ''                          | false
    }

    void "an unreadable TCK marker does not select Nitrite"() {
        given:
        def unreadable = new InputStream() {
            @Override
            int read() throws IOException {
                throw new IOException('unreadable')
            }
        }

        expect:
        !NitriteTckCompatibilityVisitor.isNitrite(unreadable)
    }

    void "without the TCK marker the loaded visitor changes nothing"() {
        given:
        def visitor = new NitriteTckCompatibilityVisitor()
        ClassElement repository = buildClassElement(REPOSITORY)
        def fields = buildClassElement(ENTITY).fields

        when:
        visitor.visitClass(repository, null)
        fields.each { visitor.visitField(it, null) }

        then:
        !repository.hasAnnotation(NitriteRepository)
        fields.every { !it.nullable && !it.hasAnnotation(Transient) }
    }

    void "under the Nitrite TCK repositories become Nitrite repositories"() {
        given:
        ClassElement repository = buildClassElement(REPOSITORY)
        ClassElement entity = buildClassElement(ENTITY)

        when:
        new NitriteTckCompatibilityVisitor(true).visitClass(repository, null)
        new NitriteTckCompatibilityVisitor(true).visitClass(entity, null)

        then:
        repository.hasAnnotation(NitriteRepository)
        !entity.hasAnnotation(NitriteRepository)
    }

    void "under the Nitrite TCK entity fields are nullable and element collections transient"() {
        given:
        def visitor = new NitriteTckCompatibilityVisitor(true)
        def fields = buildClassElement(ENTITY).fields

        when:
        fields.each { visitor.visitField(it, null) }

        then:
        fields.every { it.nullable }
        fields.find { it.name == 'tags' }.hasAnnotation(Transient)
        !fields.find { it.name == 'name' }.hasAnnotation(Transient)
    }
}
