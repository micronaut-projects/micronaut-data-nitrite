package example

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

@MicronautTest(transactional = false)
class ImmutableBookRepositorySpec extends Specification {

    @Inject ImmutableBookRepository repository

    def cleanup() {
        repository.deleteAll()
    }

    def "save and read an immutable entity with an assigned identity"() {
        given:
        def book = new ImmutableBook("book-1", "The Stand")

        when:
        def saved = repository.save(book)
        def found = repository.findById(book.id).orElseThrow()

        then:
        saved.id == book.id
        saved.title == book.title
        !found.is(book)
        found.id == book.id
        found.title == book.title

        when:
        repository.update(new ImmutableBook(book.id, "Changed"))

        then:
        repository.findById(book.id).orElseThrow().title == "Changed"
    }
}
