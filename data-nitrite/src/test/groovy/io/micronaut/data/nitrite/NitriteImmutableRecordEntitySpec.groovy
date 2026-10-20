package io.micronaut.data.nitrite

import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity
import io.micronaut.data.nitrite.annotation.NitriteRepository
import io.micronaut.data.repository.CrudRepository
import io.micronaut.data.nitrite.model.ImmutableVersionedPerson
import io.micronaut.data.nitrite.repository.ImmutableVersionedPersonRepository
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.dizitart.no2.Nitrite
import spock.lang.Specification

import java.util.UUID

@MicronautTest(transactional = false)
class NitriteImmutableRecordEntitySpec extends Specification {

    @Inject ImmutableLongPersonRepository longRepo
    @Inject ImmutablePrimitiveLongPersonRepository primitiveLongRepo
    @Inject ImmutableIntegerPersonRepository intRepo
    @Inject ImmutablePrimitiveIntPersonRepository primitiveIntRepo
    @Inject ImmutableUuidPersonRepository uuidRepo
    @Inject ImmutableVersionedPersonRepository stringRepo
    @Inject ImmutableCompositePersonRepository compositeRepo
    @Inject ImmutableMixedCompositePersonRepository mixedCompositeRepo
    @Inject Nitrite nitrite

    def cleanup() {
        longRepo.deleteAll()
        primitiveLongRepo.deleteAll()
        intRepo.deleteAll()
        primitiveIntRepo.deleteAll()
        uuidRepo.deleteAll()
        stringRepo.deleteAll()
        compositeRepo.deleteAll()
        mixedCompositeRepo.deleteAll()
    }

    def "an immutable record entity with a boxed Long identity round-trips through Nitrite document key"() {
        given:
        def person = new ImmutableLongPerson(42L, "Alice Long")

        when:
        def saved = longRepo.save(person)
        def found = longRepo.findById(42L)

        then:
        saved.id() == 42L
        saved.name() == "Alice Long"
        found.present
        found.get().id() == 42L
        found.get().name() == "Alice Long"

        and: "it is stored in Nitrite's primary document key _id"
        nitrite.getCollection("ImmutableLongPerson").find().first().getId().getIdValue() == 42L
    }

    def "an immutable record entity with a primitive long identity round-trips through Nitrite document key"() {
        given:
        def person = new ImmutablePrimitiveLongPerson(84L, "Bob Primitive Long")

        when:
        def saved = primitiveLongRepo.save(person)
        def found = primitiveLongRepo.findById(84L)

        then:
        saved.id() == 84L
        saved.name() == "Bob Primitive Long"
        found.present
        found.get().id() == 84L
        found.get().name() == "Bob Primitive Long"

        and: "it is stored in Nitrite's primary document key _id"
        nitrite.getCollection("ImmutablePrimitiveLongPerson").find().first().getId().getIdValue() == 84L
    }

    def "an immutable record entity with a boxed Integer identity round-trips correctly"() {
        given:
        def person = new ImmutableIntegerPerson(1001, "Charlie Integer")

        when:
        def saved = intRepo.save(person)
        def found = intRepo.findById(1001)

        then:
        saved.id() == 1001
        saved.name() == "Charlie Integer"
        found.present
        found.get().id() == 1001
        found.get().name() == "Charlie Integer"
    }

    def "an immutable record entity with a primitive int identity round-trips correctly"() {
        given:
        def person = new ImmutablePrimitiveIntPerson(2002, "David Primitive Int")

        when:
        def saved = primitiveIntRepo.save(person)
        def found = primitiveIntRepo.findById(2002)

        then:
        saved.id() == 2002
        saved.name() == "David Primitive Int"
        found.present
        found.get().id() == 2002
        found.get().name() == "David Primitive Int"
    }

    def "an immutable record entity with a UUID identity round-trips correctly"() {
        given:
        def uuid = UUID.randomUUID()
        def person = new ImmutableUuidPerson(uuid, "Eve UUID")

        when:
        def saved = uuidRepo.save(person)
        def found = uuidRepo.findById(uuid)

        then:
        saved.id() == uuid
        saved.name() == "Eve UUID"
        found.present
        found.get().id() == uuid
        found.get().name() == "Eve UUID"
    }

    def "an immutable record entity with an assigned String identity round-trips correctly"() {
        given:
        def person = new ImmutableVersionedPerson("str-1", "Frank String", null)

        when:
        def saved = stringRepo.save(person)
        def found = stringRepo.findById("str-1")

        then:
        saved.id() == "str-1"
        saved.name() == "Frank String"
        found.present
        found.get().id() == "str-1"
        found.get().name() == "Frank String"
        found.get().version() == 0L
    }

    def "an immutable record entity with a composite identity round-trips correctly"() {
        given:
        def person = new ImmutableCompositePerson("tenant-x", "item-99", "Grace Composite")

        when:
        def saved = compositeRepo.save(person)
        def byFields = compositeRepo.findByTenantIdAndRefId("tenant-x", "item-99")
        def byKey = compositeRepo.findById(person)

        then:
        saved.tenantId() == "tenant-x"
        saved.refId() == "item-99"
        saved.name() == "Grace Composite"

        byFields.present
        byFields.get().tenantId() == "tenant-x"
        byFields.get().refId() == "item-99"
        byFields.get().name() == "Grace Composite"

        byKey.present
        byKey.get().tenantId() == "tenant-x"
        byKey.get().refId() == "item-99"
        byKey.get().name() == "Grace Composite"
    }

    def "an immutable record entity with a mixed composite identity (String + Long) round-trips correctly"() {
        given:
        def person = new ImmutableMixedCompositePerson("tenant-alpha", 5005L, "Henry Mixed Composite")

        when:
        def saved = mixedCompositeRepo.save(person)
        def byFields = mixedCompositeRepo.findByTenantIdAndSequenceNo("tenant-alpha", 5005L)
        def byKey = mixedCompositeRepo.findById(person)

        then:
        saved.tenantId() == "tenant-alpha"
        saved.sequenceNo() == 5005L
        saved.name() == "Henry Mixed Composite"

        byFields.present
        byFields.get().tenantId() == "tenant-alpha"
        byFields.get().sequenceNo() == 5005L
        byFields.get().name() == "Henry Mixed Composite"

        byKey.present
        byKey.get().tenantId() == "tenant-alpha"
        byKey.get().sequenceNo() == 5005L
        byKey.get().name() == "Henry Mixed Composite"
    }
}

@MappedEntity
record ImmutableLongPerson(@Id Long id, String name) {}

@MappedEntity
record ImmutablePrimitiveLongPerson(@Id long id, String name) {}

@MappedEntity
record ImmutableIntegerPerson(@Id Integer id, String name) {}

@MappedEntity
record ImmutablePrimitiveIntPerson(@Id int id, String name) {}

@MappedEntity
record ImmutableUuidPerson(@Id UUID id, String name) {}

@MappedEntity
record ImmutableCompositePerson(@Id String tenantId, @Id String refId, String name) {}

@MappedEntity
record ImmutableMixedCompositePerson(@Id String tenantId, @Id Long sequenceNo, String name) {}

@NitriteRepository
interface ImmutableLongPersonRepository extends CrudRepository<ImmutableLongPerson, Long> {}

@NitriteRepository
interface ImmutablePrimitiveLongPersonRepository extends CrudRepository<ImmutablePrimitiveLongPerson, Long> {}

@NitriteRepository
interface ImmutableIntegerPersonRepository extends CrudRepository<ImmutableIntegerPerson, Integer> {}

@NitriteRepository
interface ImmutablePrimitiveIntPersonRepository extends CrudRepository<ImmutablePrimitiveIntPerson, Integer> {}

@NitriteRepository
interface ImmutableUuidPersonRepository extends CrudRepository<ImmutableUuidPerson, UUID> {}

@NitriteRepository
interface ImmutableCompositePersonRepository extends CrudRepository<ImmutableCompositePerson, ImmutableCompositePerson> {
    Optional<ImmutableCompositePerson> findByTenantIdAndRefId(String tenantId, String refId)
}

@NitriteRepository
interface ImmutableMixedCompositePersonRepository extends CrudRepository<ImmutableMixedCompositePerson, ImmutableMixedCompositePerson> {
    Optional<ImmutableMixedCompositePerson> findByTenantIdAndSequenceNo(String tenantId, Long sequenceNo)
}
