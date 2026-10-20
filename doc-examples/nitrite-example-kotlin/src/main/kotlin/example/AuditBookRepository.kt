package example

import io.micronaut.data.nitrite.annotation.NitriteRepository
import io.micronaut.data.repository.CrudRepository

// tag::named-datasource-repository[]
@NitriteRepository("audit")
interface AuditBookRepository : CrudRepository<Book, String>
// end::named-datasource-repository[]
