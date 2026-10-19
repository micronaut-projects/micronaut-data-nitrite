package io.micronaut.data.nitrite.service

import io.micronaut.data.exceptions.DataAccessException
import io.micronaut.data.nitrite.model.Event
import io.micronaut.data.nitrite.repository.EventJdqlRepository
import io.micronaut.data.nitrite.repository.EventRepository
import io.micronaut.data.nitrite.transaction.NitriteTransactionOperations
import io.micronaut.data.repository.jpa.criteria.DeleteSpecification
import io.micronaut.data.repository.jpa.criteria.UpdateSpecification
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.transaction.TransactionDefinition
import io.micronaut.transaction.support.DefaultTransactionDefinition
import jakarta.inject.Inject
import spock.lang.Specification

@MicronautTest(transactional = false)
class NitriteReadOnlyTransactionSpec extends Specification {

    @Inject EventRepository repository
    @Inject EventJdqlRepository jdqlRepository
    @Inject NitriteTransactionOperations transactions

    def setup() {
        repository.deleteAll()
    }

    void "read-only transactions allow reads"() {
        given:
        def event = repository.save(new Event("existing", "original"))

        expect:
        transactions.executeRead {
            repository.findById(event.id).orElseThrow().payload == "original" && repository.count() == 1
        }
    }

    void "read-only transactions reject #operation without changing stored data"() {
        given:
        def event = new Event("existing", "original")
        event.amount = 100G
        repository.save(event)

        when:
        transactions.executeRead {
            write(repository, jdqlRepository, event)
        }

        then:
        def error = thrown(DataAccessException)
        error.message == "Cannot execute a write operation in a read-only Nitrite transaction"
        repository.count() == 1
        with(repository.findById(event.id).orElseThrow()) {
            payload == "original"
            priority == 5
            amount == 100G
        }

        where:
        operation          | write
        "save"             | { r, j, e -> r.save(new Event("new", "new")) }
        "saveAll"          | { r, j, e -> r.saveAll([new Event("new", "new")]) }
        "update"           | { r, j, e -> e.payload = "changed"; r.update(e) }
        "updateAll"        | { r, j, e -> e.payload = "changed"; r.updateAll([e]) }
        "delete"           | { r, j, e -> r.delete(e) }
        "deleteAll batch"  | { r, j, e -> r.deleteAll([e]) }
        "deleteAll"        | { r, j, e -> r.deleteAll() }
        "deleteById"       | { r, j, e -> r.deleteById(e.id) }
        "JSON update"      | { r, j, e -> r.incrementPriority(e.type, 1) }
        "JDQL update"      | { r, j, e -> j.subtractAmountByType(e.type, 10G) }
        "criteria update"  | { r, j, e -> r.updateAll({ root, query, cb ->
            query.set("payload", "changed")
            cb.equal(root.get("id"), e.id)
        } as UpdateSpecification<Event>) }
        "criteria delete"  | { r, j, e -> r.deleteAll({ root, query, cb ->
            cb.equal(root.get("id"), e.id)
        } as DeleteSpecification<Event>) }
    }

    void "a participating write transaction cannot override read-only"() {
        when:
        transactions.executeRead {
            transactions.executeWrite {
                repository.save(new Event("rejected", "new"))
            }
        }

        then:
        thrown(DataAccessException)
        repository.count() == 0
    }

    void "a participating read transaction retains the outer writable setting"() {
        when:
        transactions.executeWrite {
            transactions.executeRead {
                repository.save(new Event("allowed", "new"))
            }
        }

        then:
        repository.count() == 1
    }

    void "a read-only transaction is restored after requires-new with participating wrapper #participating"() {
        given:
        def requiresNew = new DefaultTransactionDefinition(TransactionDefinition.Propagation.REQUIRES_NEW)
        def writeIndependently = {
            transactions.execute(requiresNew) {
                repository.save(new Event("independent", "new"))
            }
            repository.save(new Event("rejected", "new"))
        }

        when:
        transactions.executeRead {
            if (participating) {
                transactions.executeWrite { writeIndependently() }
            } else {
                writeIndependently()
            }
        }

        then:
        thrown(DataAccessException)
        repository.findByType("independent").size() == 1
        repository.findByType("rejected").isEmpty()

        where:
        participating << [false, true]
    }

    void "a writable transaction is restored after an independent read-only transaction"() {
        given:
        def requiresNewReadOnly = new DefaultTransactionDefinition(TransactionDefinition.Propagation.REQUIRES_NEW)
        requiresNewReadOnly.setReadOnly(true)

        when:
        transactions.executeWrite {
            try {
                transactions.execute(requiresNewReadOnly) {
                    repository.save(new Event("rejected", "new"))
                }
                assert false: "The independent read-only transaction must reject writes"
            } catch (DataAccessException expected) {
                repository.save(new Event("outer", "new"))
            }
        }

        then:
        repository.findByType("outer").size() == 1
        repository.findByType("rejected").isEmpty()
    }
}
