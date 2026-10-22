package io.micronaut.data.nitrite.transaction

import org.dizitart.no2.Nitrite
import org.dizitart.no2.collection.Document
import org.dizitart.no2.transaction.Session
import org.dizitart.no2.transaction.Transaction
import spock.lang.AutoCleanup
import spock.lang.Specification

class NitriteTransactionContextSpec extends Specification {

    @AutoCleanup
    Nitrite database = Nitrite.builder().openOrCreate()

    void "the compatibility constructor exposes the active transaction with a writable policy"() {
        given:
        database.getCollection("context").insert(Document.createDocument("value", "existing"))
        Session session = database.createSession()
        Transaction transaction = session.beginTransaction()

        when:
        def context = new NitriteTransactionContext(transaction)

        then:
        !context.readOnly
        context.transaction.is(transaction)
        context.getCollection("context").find().toList()*.get("value") == ["existing"]

        cleanup:
        transaction.close()
        session.close()
    }
}
