package example

import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity

// tag::immutable-entity[]
@MappedEntity
data class ImmutableBook(@field:Id val id: String, val title: String)
// end::immutable-entity[]
