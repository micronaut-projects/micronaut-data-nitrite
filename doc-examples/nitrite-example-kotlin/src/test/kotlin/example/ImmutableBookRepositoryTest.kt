package example

import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame

@MicronautTest(transactional = false)
class ImmutableBookRepositoryTest {

    @Inject
    lateinit var repository: ImmutableBookRepository

    @AfterEach
    fun cleanup() {
        repository.deleteAll()
    }

    @Test
    fun saveAndReadAnImmutableEntityWithAnAssignedIdentity() {
        val book = ImmutableBook("book-1", "The Stand")
        assertEquals(book, repository.save(book))

        val found = repository.findById(book.id).orElseThrow()
        assertNotSame(book, found)
        assertEquals(book, found)

        repository.update(ImmutableBook(book.id, "Changed"))
        assertEquals("Changed", repository.findById(book.id).orElseThrow().title)
    }
}
