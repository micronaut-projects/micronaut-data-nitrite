package io.micronaut.data.nitrite.processor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import jakarta.data.repository.Query
import spock.lang.Unroll

class NitriteNativeQueryVisitorSpec extends AbstractTypeElementSpec {

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

    void "compilation adds the JDQL query to native query methods only"() {
        when:
        def definition = buildBeanDefinition('test.Products', '''
package test;

import io.micronaut.context.annotation.Executable;
import jakarta.data.repository.Query;
import jakarta.inject.Singleton;
import jakarta.persistence.query.NativeQuery;

@Singleton
class Products {
    @Executable
    @NativeQuery("SELECT * FROM Product WHERE price > ?")
    Object nativeOnly(int price) {
        return null;
    }

    @Executable
    @Query("FROM Product WHERE name = ?1")
    @NativeQuery("SELECT * FROM Product WHERE price > ?")
    Object both(String name) {
        return null;
    }

    @Executable
    Object plain() {
        return null;
    }
}
''')

        then:
        definition.getRequiredMethod('nativeOnly', int).stringValue(Query).get() == 'FROM Product WHERE price > ?1'
        definition.getRequiredMethod('both', String).stringValue(Query).get() == 'FROM Product WHERE name = ?1'
        !definition.getRequiredMethod('plain').hasAnnotation(Query)
    }
}
