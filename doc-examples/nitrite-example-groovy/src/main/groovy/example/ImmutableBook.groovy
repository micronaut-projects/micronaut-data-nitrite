package example

import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity

// tag::immutable-entity[]
@MappedEntity
class ImmutableBook {
    @Id
    final String id
    final String title

    ImmutableBook(String id, String title) {
        this.id = id
        this.title = title
    }
}
// end::immutable-entity[]
