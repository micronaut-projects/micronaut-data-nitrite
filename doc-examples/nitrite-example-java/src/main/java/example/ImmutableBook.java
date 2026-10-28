package example;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

// tag::immutable-entity[]
@MappedEntity
public record ImmutableBook(@Id String id, String title) {
}
// end::immutable-entity[]
