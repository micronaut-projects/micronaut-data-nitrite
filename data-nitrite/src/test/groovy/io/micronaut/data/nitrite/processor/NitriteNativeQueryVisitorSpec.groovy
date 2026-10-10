package io.micronaut.data.nitrite.processor

import spock.lang.Specification
import spock.lang.Unroll

class NitriteNativeQueryVisitorSpec extends Specification {

    @Unroll
    void "native SQL '#sql' runs as JDQL '#jdql'"() {
        expect:
        NitriteNativeQueryVisitor.toJdql(sql) == jdql

        where:
        sql                                                       | jdql
        "SELECT * FROM Product WHERE price >= ? AND price <= ?"   | "FROM Product WHERE price >= ?1 AND price <= ?2"
        "SELECT COUNT(*) FROM Product WHERE price > ?"            | "SELECT COUNT(THIS) FROM Product WHERE price > ?1"
        "UPDATE Product SET price = ? WHERE productNum = ?"       | "UPDATE Product SET price = ?1 WHERE productNum = ?2"
        "DELETE FROM Product WHERE name = '?' AND versionNum = ?" | "DELETE FROM Product WHERE name = '?' AND versionNum = ?1"
        "SELECT name FROM Product WHERE productNum = ?1"          | "SELECT name FROM Product WHERE productNum = ?1"
    }
}
