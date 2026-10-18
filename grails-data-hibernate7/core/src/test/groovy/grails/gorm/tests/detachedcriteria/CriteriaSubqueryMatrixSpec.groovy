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
 * Semantics of the comparison subqueries against a real database: ALL and SOME over an empty
 * result, NULL members, duplicates, and the value-list forms. SQL defines {@code x > ALL (empty)}
 * and {@code x = ALL (empty)} as TRUE and {@code x > SOME (empty)} as FALSE, and a NULL member
 * turns the comparison it takes part in into NULL, which ALL treats as a failure and SOME ignores.
 * NOT IN over a member list containing NULL matches nothing.
 */
class CriteriaSubqueryMatrixSpec extends HibernateGormDatastoreSpec {

    def setupSpec() {
        manager.registerDomainClasses(SubqueryMatrixEntity)
    }

    private void seedValues() {
        new SubqueryMatrixEntity(name: 'a', amount: 1).save(flush: true)
        new SubqueryMatrixEntity(name: 'b', amount: 2).save(flush: true)
        new SubqueryMatrixEntity(name: 'c', amount: 3).save(flush: true)
        new SubqueryMatrixEntity(name: 'd', amount: null).save(flush: true)
        new SubqueryMatrixEntity(name: 'e', amount: 2).save(flush: true)
    }

    private static DetachedCriteria<SubqueryMatrixEntity> valuesOf(String... names) {
        SubqueryMatrixEntity.where {
            projections { property('amount') }
            inList('name', names as List)
        }
    }

    private static DetachedCriteria<SubqueryMatrixEntity> noValues() {
        SubqueryMatrixEntity.where {
            projections { property('amount') }
            eq('name', 'no-such-name')
        }
    }

    void "test gtAll over an empty subquery is vacuously true"() {
        given:
        seedValues()

        expect: 'every row satisfies > ALL (empty), including the row whose value is null'
        SubqueryMatrixEntity.where { gtAll('amount', noValues()) }.list()*.name.sort() == ['a', 'b', 'c', 'd', 'e']
    }

    void "test eqAll over an empty subquery is vacuously true"() {
        given:
        seedValues()

        expect:
        SubqueryMatrixEntity.where { eqAll('amount', noValues()) }.list()*.name.sort() == ['a', 'b', 'c', 'd', 'e']
    }

    void "test gtSome over an empty subquery is false"() {
        given:
        seedValues()

        expect:
        SubqueryMatrixEntity.where { gtSome('amount', noValues()) }.list().isEmpty()
    }

    void "test a NULL member makes gtAll false and is ignored by gtSome"() {
        given:
        seedValues()

        expect: 'one NULL comparison is enough for ALL to fail'
        SubqueryMatrixEntity.where { gtAll('amount', valuesOf('a', 'd')) }.list().isEmpty()

        and: 'SOME only needs one TRUE comparison'
        SubqueryMatrixEntity.where { gtSome('amount', valuesOf('a', 'd')) }.list()*.name.sort() == ['b', 'c', 'e']
    }

    void "test duplicate subquery members do not change gtAll"() {
        given:
        seedValues()

        expect:
        SubqueryMatrixEntity.where { gtAll('amount', valuesOf('b', 'e')) }.list()*.name == ['c']
    }

    void "test inList over an empty subquery matches nothing"() {
        given:
        seedValues()

        expect:
        SubqueryMatrixEntity.where { inList('amount', noValues()) }.list().isEmpty()
    }

    void "test notIn over a subquery with a NULL member matches nothing"() {
        given:
        seedValues()

        expect: 'NOT (value IN (1, NULL)) is NULL for every row, the SQL NOT IN trap'
        SubqueryMatrixEntity.where { notIn('amount', valuesOf('a', 'd')) }.list().isEmpty()
    }

    void "test eq and ne with a subquery keep the comparison semantics"() {
        given:
        seedValues()

        expect:
        SubqueryMatrixEntity.where { eq('amount', valuesOf('a')) }.list()*.name == ['a']

        and: 'ne keeps rows whose value is null, like the value form does'
        SubqueryMatrixEntity.where { ne('amount', valuesOf('a')) }.list()*.name.sort() == ['b', 'c', 'd', 'e']
    }

    void "test two subqueries combine with and and or in one query"() {
        given:
        seedValues()

        expect: 'the intersection of both selections is empty'
        SubqueryMatrixEntity.where {
            inList('name', namesWithAmount(2))
            inList('amount', valuesOf('c'))
        }.list().isEmpty()

        and: 'the union of both selections'
        SubqueryMatrixEntity.where {
            or {
                inList('name', namesWithAmount(2))
                inList('amount', valuesOf('c'))
            }
        }.list()*.name.sort() == ['b', 'c', 'e']
    }

    void "test nested exists two levels deep"() {
        given:
        seedValues()

        expect: 'a row exists for which a row named a exists'
        SubqueryMatrixEntity.where {
            exists(SubqueryMatrixEntity.where {
                exists(SubqueryMatrixEntity.where { eq('name', 'a') })
            })
        }.list()*.name.sort() == ['a', 'b', 'c', 'd', 'e']

        and: 'the inner restriction is respected'
        SubqueryMatrixEntity.where {
            exists(SubqueryMatrixEntity.where {
                exists(SubqueryMatrixEntity.where { eq('name', 'no-such-name') })
            })
        }.list().isEmpty()
    }

    void "test count and pagination work with a subquery criterion"() {
        given:
        seedValues()

        expect:
        SubqueryMatrixEntity.where { gtSome('amount', valuesOf('a', 'd')) }.count() == 3

        when:
        def page = SubqueryMatrixEntity.where { gtSome('amount', valuesOf('a', 'd')) }.list(max: 2)

        then: 'the page is limited while the total count sees every match'
        page.size() == 2
        page.totalCount == 3
    }

    void "test the other read terminals work with a subquery criterion"() {
        given:
        seedValues()

        when:
        def matching = SubqueryMatrixEntity.where { gtSome('amount', valuesOf('a', 'd')) }

        then:
        matching.size() == 3
        matching.asBoolean()
        matching.iterator().collect { it.name }.sort() == ['b', 'c', 'e']

        when: 'no row matches'
        def none = SubqueryMatrixEntity.where { gtSome('amount', valuesOf('no-such-name')) }

        then:
        !none.asBoolean()
        none.count() == 0
    }

    void "test count and pagination with a correlated subquery"() {
        given:
        seedValues()

        when:
        def correlated = SubqueryMatrixEntity.where {
            exists(SubqueryMatrixEntity.where {
                eqProperty('name', '{alias}.name')
                eq('amount', 2)
            })
        }

        then: 'only the rows named like a row with amount 2'
        correlated.count() == 2
        correlated.list()*.name.sort() == ['b', 'e']

        and: 'the page is limited while the total count sees every match'
        def page = correlated.list(max: 1)
        page.size() == 1
        page.totalCount == 2
    }

    private static DetachedCriteria<SubqueryMatrixEntity> namesWithAmount(int amount) {
        SubqueryMatrixEntity.where {
            projections { property('name') }
            eq('amount', amount)
        }
    }
}

@Entity
class SubqueryMatrixEntity implements HibernateEntity<SubqueryMatrixEntity> {

    String name
    Integer amount
}
