package example;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

@MicronautTest(transactional = false)
class ImmutableBookRepositoryTest {

    @Inject
    ImmutableBookRepository repository;

    @AfterEach
    void cleanup() {
        repository.deleteAll();
    }

    @Test
    void saveAndReadAnImmutableEntityWithAnAssignedIdentity() {
        ImmutableBook book = new ImmutableBook("book-1", "The Stand");
        assertEquals(book, repository.save(book));

        ImmutableBook found = repository.findById(book.id()).orElseThrow();
        assertNotSame(book, found);
        assertEquals(book, found);

        repository.update(new ImmutableBook(book.id(), "Changed"));
        assertEquals("Changed", repository.findById(book.id()).orElseThrow().title());
    }
}
