/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package grails.gorm.tests.detachedcriteria

import grails.gorm.DetachedCriteria
import grails.gorm.annotation.Entity
import grails.gorm.hibernate.HibernateEntity
import grails.gorm.tests.HibernateGormDatastoreSpec
import org.springframework.dao.InvalidDataAccessResourceUsageException

/**
 * Bulk writes that select their rows with a subquery. The mutation path builds HQL through
 * JpaQueryBuilder, which supports the subquery criteria and rejects anything it does not
 * understand, so a write must never fall back to touching every row: the tests assert the rows
 * that changed, the rows that did not, and the count each operation returns.
 *
 * More than one subquery in the same statement is covered on purpose: the builder numbers its
 * parameters as it walks the criteria, and a subquery that did not report the parameters it
 * consumed made the next criterion reuse a parameter name with another type or value.
 */
class CriteriaWriteOperationsSpec extends HibernateGormDatastoreSpec {

    def setupSpec() {
        manager.registerDomainClasses(WriteOperationEntity)
    }

    private void seed() {
        new WriteOperationEntity(name: 'a', amount: 1).save(flush: true)
        new WriteOperationEntity(name: 'b', amount: 2).save(flush: true)
        new WriteOperationEntity(name: 'c', amount: 3).save(flush: true)
        new WriteOperationEntity(name: 'd', amount: null).save(flush: true)
        new WriteOperationEntity(name: 'e', amount: 2).save(flush: true)
    }

    private static DetachedCriteria<WriteOperationEntity> valuesOf(String... names) {
        WriteOperationEntity.where {
            projections { property('amount') }
            inList('name', names as List)
        }
    }

    private static DetachedCriteria<WriteOperationEntity> namesWithAmount(int amount) {
        WriteOperationEntity.where {
            projections { property('name') }
            eq('amount', amount)
        }
    }

    private static List<String> remainingNames() {
        WriteOperationEntity.where {}.list()*.name.sort()
    }

    void "test updateAll selects its rows with a subquery"() {
        given:
        seed()

        when:
        Number updated = WriteOperationEntity.where { inList('amount', valuesOf('a', 'b')) }.updateAll([name: 'updated'])
        manager.session.clear()

        then: 'only the selected rows are updated and the count matches'
        updated == 3
        WriteOperationEntity.where { inList('amount', [1, 2]) }.list()*.name.unique() == ['updated']
        WriteOperationEntity.findByName('c').name == 'c'
        WriteOperationEntity.findByName('d').name == 'd'
    }

    void "test deleteAll selects its rows with a subquery"() {
        given:
        seed()

        when:
        Number deleted = WriteOperationEntity.where { inList('amount', valuesOf('a', 'b')) }.deleteAll()
        manager.session.clear()

        then:
        deleted == 3
        remainingNames() == ['c', 'd']
    }

    void "test a write with no matching rows changes nothing"() {
        given:
        seed()

        when:
        Number updated = WriteOperationEntity.where { eq('name', 'no-such-name') }.updateAll([name: 'updated'])
        Number deleted = WriteOperationEntity.where { eq('name', 'no-such-name') }.deleteAll()

        then:
        updated == 0
        deleted == 0
        remainingNames() == ['a', 'b', 'c', 'd', 'e']
    }

    void "test a write selected by an empty subquery changes nothing"() {
        given:
        seed()

        when:
        Number deleted = WriteOperationEntity.where { inList('amount', valuesOf('no-such-name')) }.deleteAll()

        then:
        deleted == 0
        remainingNames() == ['a', 'b', 'c', 'd', 'e']
    }

    void "test a notIn subquery write keeps the null rows, like SQL does"() {
        given:
        seed()

        when:
        Number deleted = WriteOperationEntity.where { notIn('amount', valuesOf('a', 'b')) }.deleteAll()

        then: 'NOT IN keeps NULL rows out of the selection, so the null row is not deleted'
        deleted == 1
        remainingNames() == ['a', 'b', 'd', 'e']
    }

    void "test updateAll with two in subqueries combines them with and"() {
        given:
        seed()

        when: 'rows whose amount is in (1, 2) and whose name belongs to the rows with amount 2'
        Number updated = WriteOperationEntity.where {
            inList('amount', valuesOf('a', 'b'))
            inList('name', namesWithAmount(2))
        }.updateAll([name: 'updated'])
        manager.session.clear()

        then: 'only the intersection changes'
        updated == 2
        WriteOperationEntity.where { inList('amount', [1, 2]) }.list()*.name.sort() == ['a', 'updated', 'updated']
        WriteOperationEntity.findByName('c').name == 'c'
        WriteOperationEntity.findByName('d').name == 'd'
    }

    void "test deleteAll with a disjunction of two in subqueries"() {
        given:
        seed()

        when:
        Number deleted = WriteOperationEntity.where {
            or {
                inList('amount', valuesOf('a'))
                inList('amount', valuesOf('b'))
            }
        }.deleteAll()
        manager.session.clear()

        then: 'the union of both selections is deleted'
        deleted == 3
        remainingNames() == ['c', 'd']
    }

    void "test a write with an empty in value list changes nothing"() {
        given:
        seed()

        when:
        Number deleted = WriteOperationEntity.where { inList('name', []) }.deleteAll()

        then: 'an empty selection matches no row instead of failing or widening the write'
        deleted == 0
        remainingNames() == ['a', 'b', 'c', 'd', 'e']
    }

    void "test a write selected by an exists subquery is rejected loudly"() {
        given:
        seed()
        def existsSubquery = WriteOperationEntity.where {
            projections { property('amount') }
            eq('name', 'a')
        }

        when: 'the mutation path does not support exists yet'
        WriteOperationEntity.where { exists(existsSubquery) }.deleteAll()

        then: 'it fails with a clear message instead of deleting every row'
        InvalidDataAccessResourceUsageException e = thrown()
        e.message.contains('not supported')
        WriteOperationEntity.count() == 5

        when: 'the same for notExists and updateAll'
        WriteOperationEntity.where { notExists(existsSubquery) }.updateAll([name: 'updated'])

        then:
        thrown(InvalidDataAccessResourceUsageException)
        remainingNames() == ['a', 'b', 'c', 'd', 'e']
    }
}

@Entity
class WriteOperationEntity implements HibernateEntity<WriteOperationEntity> {

    String name
    Integer amount
}
