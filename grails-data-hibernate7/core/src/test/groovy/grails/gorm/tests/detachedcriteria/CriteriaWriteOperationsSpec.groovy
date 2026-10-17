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

/**
 * Bulk writes that select their rows with a subquery. The mutation path builds HQL through
 * JpaQueryBuilder, which supports the subquery criteria and rejects anything it does not
 * understand, so a write must never fall back to touching every row: the tests assert the rows
 * that changed, the rows that did not, and the count each operation returns.
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
    }

    private static DetachedCriteria<WriteOperationEntity> valuesOf(String... names) {
        WriteOperationEntity.where {
            projections { property('amount') }
            inList('name', names as List)
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
        updated == 2
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
        deleted == 2
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
        remainingNames() == ['a', 'b', 'c', 'd']
    }

    void "test a write selected by an empty subquery changes nothing"() {
        given:
        seed()

        when:
        Number deleted = WriteOperationEntity.where { inList('amount', valuesOf('no-such-name')) }.deleteAll()

        then:
        deleted == 0
        remainingNames() == ['a', 'b', 'c', 'd']
    }

    void "test a notIn subquery write keeps the null rows, like SQL does"() {
        given:
        seed()

        when:
        Number deleted = WriteOperationEntity.where { notIn('amount', valuesOf('a', 'b')) }.deleteAll()

        then: 'NOT IN keeps NULL rows out of the selection, so the null row is not deleted'
        deleted == 1
        remainingNames() == ['a', 'b', 'd']
    }
}

@Entity
class WriteOperationEntity implements HibernateEntity<WriteOperationEntity> {

    String name
    Integer amount
}
