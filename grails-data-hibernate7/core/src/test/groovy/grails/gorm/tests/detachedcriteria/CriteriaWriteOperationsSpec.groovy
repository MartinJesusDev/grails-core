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
        manager.registerDomainClasses(WriteOperationEntity, WriteOperationParent, WriteOperationChild, VersionedWriteEntity)
    }

    private void seed() {
        new WriteOperationEntity(name: 'a', amount: 1, otherAmount: 0, active: true, status: WriteStatus.ACTIVE).save(flush: true)
        new WriteOperationEntity(name: 'b', amount: 2, otherAmount: 1, active: true, status: WriteStatus.ACTIVE).save(flush: true)
        new WriteOperationEntity(name: 'c', amount: 3, otherAmount: 2, active: false, status: WriteStatus.INACTIVE).save(flush: true)
        new WriteOperationEntity(name: 'd', amount: null, otherAmount: null, active: false).save(flush: true)
        new WriteOperationEntity(name: 'e', amount: 2, otherAmount: 1, active: true, status: WriteStatus.ACTIVE).save(flush: true)
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

    void "test a write selected by an exists subquery follows the subquery result"() {
        given:
        seed()

        when: 'no row matches the subquery'
        Number noneDeleted = WriteOperationEntity.where {
            exists(WriteOperationEntity.where { eq('name', 'no-such-name') })
        }.deleteAll()

        then:
        noneDeleted == 0
        remainingNames() == ['a', 'b', 'c', 'd', 'e']

        when: 'the subquery matches a row'
        Number allDeleted = WriteOperationEntity.where {
            exists(WriteOperationEntity.where { eq('name', 'a') })
        }.deleteAll()
        manager.session.clear()

        then: 'an uncorrelated exists is true for every outer row'
        allDeleted == 5
        remainingNames() == []
    }

    void "test updateAll selects its rows with a notExists subquery"() {
        given:
        seed()

        when: 'the subquery matches nothing, so NOT EXISTS is true for every row'
        Number updated = WriteOperationEntity.where {
            notExists(WriteOperationEntity.where { eq('name', 'no-such-name') })
        }.updateAll([name: 'updated'])
        manager.session.clear()

        then:
        updated == 5
        WriteOperationEntity.where {}.list()*.name.unique() == ['updated']
    }

    void "test a cross-entity write selects rows through an association subquery"() {
        given:
        def parentA = new WriteOperationParent(name: 'p-a').save(flush: true)
        def parentB = new WriteOperationParent(name: 'p-b').save(flush: true)
        new WriteOperationChild(parent: parentA, name: 'child').save(flush: true)
        new WriteOperationChild(parent: parentB, name: 'other').save(flush: true)

        when: 'children of the parents selected by the subquery'
        Number deleted = WriteOperationChild.where {
            inList('parent', WriteOperationParent.where { eq('name', 'p-a') })
        }.deleteAll()
        manager.session.clear()

        then: 'only the matching child is deleted and the parents are untouched'
        deleted == 1
        WriteOperationChild.where {}.list()*.name == ['other']
        WriteOperationParent.where {}.list()*.name.sort() == ['p-a', 'p-b']
    }

    void "test a write filters by the id of a to-one association without a join"() {
        given:
        def parentA = new WriteOperationParent(name: 'p-a').save(flush: true)
        def parentB = new WriteOperationParent(name: 'p-b').save(flush: true)
        new WriteOperationChild(parent: parentA, name: 'child-a').save(flush: true)
        new WriteOperationChild(parent: parentB, name: 'child-b').save(flush: true)

        when: 'the read path resolves the association id'
        def byWhereSyntax = WriteOperationChild.where { parent.id == parentA.id }.list()*.name
        def byExplicit = WriteOperationChild.where { eq('parent.id', parentB.id) }.list()*.name

        then:
        byWhereSyntax == ['child-a']
        byExplicit == ['child-b']

        when: 'the where query syntax filters a bulk delete by the association id'
        Number deleted = WriteOperationChild.where { parent.id == parentA.id }.deleteAll()
        manager.session.clear()

        then: 'the association id is an id access, not a join, so the delete is allowed'
        deleted == 1
        WriteOperationChild.where {}.list()*.name == ['child-b']
    }

    void "test an update filters by the id of a to-one association without a join"() {
        given:
        def parentA = new WriteOperationParent(name: 'p-a').save(flush: true)
        def parentB = new WriteOperationParent(name: 'p-b').save(flush: true)
        new WriteOperationChild(parent: parentA, name: 'child-a').save(flush: true)
        new WriteOperationChild(parent: parentB, name: 'child-b').save(flush: true)

        when:
        Number updated = WriteOperationChild.where { eq('parent.id', parentB.id) }.updateAll([name: 'updated'])
        manager.session.clear()

        then:
        updated == 1
        WriteOperationChild.where {}.list()*.name.sort() == ['child-a', 'updated']
    }

    void "test a write filters through a to-one association property"() {
        given:
        def parentA = new WriteOperationParent(name: 'p-a').save(flush: true)
        def parentB = new WriteOperationParent(name: 'p-b').save(flush: true)
        new WriteOperationChild(parent: parentA, name: 'child-a').save(flush: true)
        new WriteOperationChild(parent: parentB, name: 'child-b').save(flush: true)

        when: 'the where query syntax filters by a property of the association'
        Number deleted = WriteOperationChild.where { parent.name == 'p-a' }.deleteAll()
        manager.session.clear()

        then:
        deleted == 1
        WriteOperationChild.where {}.list()*.name == ['child-b']
    }

    void "test deleteAll combines several subqueries and junctions with mixed types"() {
        given:
        seed()

        when: 'an Integer subquery, a String subquery and a Long subquery in one statement'
        def amountsOfB = valuesOf('b')
        def namesOfAmount2 = namesWithAmount(2)
        def activeIds = WriteOperationEntity.where {
            projections { property('id') }
            eq('active', true)
        }
        Number deleted = WriteOperationEntity.where {
            and {
                or {
                    inList('amount', amountsOfB)
                    inList('name', namesOfAmount2)
                }
                inList('id', activeIds)
                not { inList('amount', valuesOf('a')) }
            }
        }.deleteAll()
        manager.session.clear()

        then: 'the rows that satisfy the whole conjunction are deleted'
        deleted == 2
        remainingNames() == ['a', 'c', 'd']
    }

    void "test updateAll combines several subqueries with a multi-property set"() {
        given:
        seed()

        when: 'two updated properties and a where clause with a range and two subqueries'
        Number updated = WriteOperationEntity.where {
            and {
                between('amount', 1, 2)
                or {
                    inList('name', namesWithAmount(2))
                    inList('id', WriteOperationEntity.where {
                        projections { property('id') }
                        eq('active', false)
                    })
                }
            }
        }.updateAll([name: 'updated', amount: 99])
        manager.session.clear()

        then: 'the set parameters and the where parameters do not collide'
        updated == 2
        WriteOperationEntity.where { eq('amount', 99) }.list()*.name.sort() == ['updated', 'updated']
        WriteOperationEntity.where {}.list().collect { [it.name, it.amount] }.sort { it[0] } ==
                [['a', 1], ['c', 3], ['d', null], ['updated', 99], ['updated', 99]]
    }

    void "test a write combines a subquery that itself contains a subquery"() {
        given:
        seed()

        when: 'the inner subquery selects the amounts of the active rows and the outer one filters by them'
        def activeAmounts = WriteOperationEntity.where {
            projections { property('amount') }
            eq('active', true)
        }
        def amountsMatchingActive = WriteOperationEntity.where {
            projections { property('amount') }
            inList('amount', activeAmounts)
        }
        Number deleted = WriteOperationEntity.where { inList('amount', amountsMatchingActive) }.deleteAll()
        manager.session.clear()

        then:
        deleted == 3
        remainingNames() == ['c', 'd']
    }

    void "test a write supports nested exists two levels deep"() {
        given:
        seed()

        when:
        Number deleted = WriteOperationEntity.where {
            exists(WriteOperationEntity.where {
                exists(WriteOperationEntity.where { eq('name', 'a') })
            })
        }.deleteAll()
        manager.session.clear()

        then: 'the uncorrelated exists is true for every row'
        deleted == 5
        remainingNames() == []
    }

    void "test deleteAll filters by the association id with #operator"() {
        given:
        def (parentAId, parentBId) = seedParentsAndChildren()

        when:
        Number deleted = WriteOperationChild.where(associationIdCriteria(operator, parentAId, parentBId)).deleteAll()
        manager.session.clear()

        then:
        deleted == expectedCount
        WriteOperationChild.where {}.list()*.name.sort() == expectedRemaining

        where:
        operator  | expectedCount | expectedRemaining
        'in'      | 1             | ['child-b']
        'gt'      | 1             | ['child-a']
        'lt'      | 0             | ['child-a', 'child-b']
        'ge'      | 2             | []
        'le'      | 1             | ['child-b']
        'between' | 2             | []
        'ne'      | 1             | ['child-a']
        'isNull'  | 0             | ['child-a', 'child-b']
    }

    void "test updateAll filters by the association id with a range"() {
        given:
        def (parentAId, parentBId) = seedParentsAndChildren()

        when:
        Number updated = WriteOperationChild.where { gt('parent.id', parentAId) }.updateAll([name: 'updated'])
        manager.session.clear()

        then:
        updated == 1
        WriteOperationChild.where {}.list()*.name.sort() == ['child-a', 'updated']
    }

    void "test a write filters by the association property and combines it in a junction"() {
        given:
        def (parentAId, parentBId) = seedParentsAndChildren()

        when: 'a String comparison through the association and a disjunction of both forms'
        def byName = WriteOperationChild.where { like('parent.name', 'p-a') }.list()*.name
        def byJunction = WriteOperationChild.where {
            or {
                gt('parent.id', parentBId)
                like('parent.name', 'p-a')
            }
        }.list()*.name.sort()

        then:
        byName == ['child-a']
        byJunction == ['child-a']
    }

    void "test updateAll filters by the size of a to-many association"() {
        given:
        def (parentAId, parentBId) = seedParentsAndChildren()

        when:
        Number updated = WriteOperationParent.where { sizeEq('children', 1) }.updateAll([name: 'one-child'])
        manager.session.clear()

        then:
        updated == 2
        WriteOperationParent.where {}.list()*.name.unique() == ['one-child']
    }

    void "test a bulk write resolves a created alias to the association path"() {
        given:
        def (parentAId, parentBId) = seedParentsAndChildren()

        when: 'the alias is declared with createAlias and the criterion uses the alias'
        Number deleted = WriteOperationChild.where {
            createAlias('parent', 'p')
            eq('p.id', parentAId)
        }.deleteAll()
        manager.session.clear()

        then: 'the alias resolves to the association path and no join is needed'
        deleted == 1
        WriteOperationChild.where {}.list()*.name.sort() == ['child-b']
    }

    void "test a bulk write subquery resolves the alias placeholder"() {
        given:
        def (parentAId, parentBId) = seedParentsAndChildren()

        when:
        Number updated = WriteOperationParent.where {
            exists(WriteOperationChild.where {
                eqProperty('parent.id', '{alias}.id')
                eq('name', 'child-a')
            })
        }.updateAll([name: 'matched'])
        manager.session.clear()

        then: 'the correlated exists selects the parent of child-a'
        updated == 1
        WriteOperationParent.where {}.list()*.name.sort() == ['matched', 'p-b']
    }

    void "test deleteAll with all and some subquery comparisons"() {
        given:
        seed()

        when: 'rows whose amount is greater than every amount of a'
        Number allDeleted = WriteOperationEntity.where { gtAll('amount', valuesOf('a')) }.deleteAll()
        manager.session.clear()

        then:
        allDeleted == 3
        remainingNames() == ['a', 'd']

        when: 'rows whose amount is less than or equal to some amount of a'
        Number someDeleted = WriteOperationEntity.where { leSome('amount', valuesOf('a')) }.deleteAll()
        manager.session.clear()

        then:
        someDeleted == 1
        remainingNames() == ['d']

        when: 'the subquery matches nothing, so = ALL is vacuously true'
        Number vacuousDeleted = WriteOperationEntity.where { eqAll('amount', valuesOf('no-such-name')) }.deleteAll()
        manager.session.clear()

        then:
        vacuousDeleted == 1
        remainingNames() == []
    }

    void "test updateAll with isEmpty over a to-many association"() {
        given:
        def parentWithChild = new WriteOperationParent(name: 'p-a')
                .addToChildren(name: 'child-a')
                .save(flush: true)
        new WriteOperationParent(name: 'p-empty').save(flush: true)

        when:
        Number updated = WriteOperationParent.where { isEmpty('children') }.updateAll([name: 'no-children'])
        manager.session.clear()

        then:
        updated == 1
        WriteOperationParent.where {}.list()*.name.sort() == ['no-children', 'p-a']
    }

    void "test a bulk write resolves the alias placeholder inside an in subquery"() {
        given:
        def (parentAId, parentBId) = seedParentsAndChildren()

        when:
        Number updated = WriteOperationParent.where {
            inList('id', WriteOperationChild.where {
                projections { property('parent.id') }
                eqProperty('parent.id', '{alias}.id')
                eq('name', 'child-a')
            })
        }.updateAll([name: 'matched'])
        manager.session.clear()

        then:
        updated == 1
        WriteOperationParent.where {}.list()*.name.sort() == ['matched', 'p-b']
    }

    void "test a bulk write rejects the alias placeholder outside a subquery"() {
        given:
        seed()

        when: 'the placeholder refers to no enclosing query'
        WriteOperationEntity.where { eqProperty('name', '{alias}.name') }.deleteAll()

        then:
        InvalidDataAccessResourceUsageException e = thrown()
        e.message.contains('outside of a subquery')
        WriteOperationEntity.count() == 5
    }

    void "test a bulk write rejects rlike and sqlRestriction with a clear message"() {
        given:
        seed()

        when:
        WriteOperationEntity.where { rlike('name', 'a.*') }.deleteAll()

        then:
        InvalidDataAccessResourceUsageException e = thrown()
        e.message.contains('RLike')
        WriteOperationEntity.count() == 5

        when:
        WriteOperationEntity.where { sqlRestriction('length(name) > 0') }.deleteAll()

        then:
        thrown(InvalidDataAccessResourceUsageException)
        WriteOperationEntity.count() == 5
    }

    void "test a bulk write ignores the sort of the criteria"() {
        given:
        seed()

        when:
        Number deleted = WriteOperationEntity.where { inList('amount', valuesOf('a', 'b')) }.sort('name', 'desc').deleteAll()
        manager.session.clear()

        then: 'the order is not part of a delete'
        deleted == 3
        remainingNames() == ['c', 'd']
    }

    void "test a bulk write combines an association alias with the alias placeholder"() {
        given:
        def (parentAId, parentBId) = seedParentsAndChildren()

        when:
        Number updated = WriteOperationParent.where {
            exists(WriteOperationChild.where {
                createAlias('parent', 'p')
                eqProperty('p.id', '{alias}.id')
                eq('name', 'child-a')
            })
        }.updateAll([name: 'matched'])
        manager.session.clear()

        then: 'the alias resolves inside the subquery and the placeholder to the outer query'
        updated == 1
        WriteOperationParent.where {}.list()*.name.sort() == ['matched', 'p-b']
    }

    void "test a bulk write with duplicate in values and a boolean criterion"() {
        given:
        seed()

        when:
        Number deleted = WriteOperationEntity.where {
            inList('name', ['a', 'a'])
            eq('active', true)
        }.deleteAll()
        manager.session.clear()

        then:
        deleted == 1
        remainingNames() == ['b', 'c', 'd', 'e']
    }

    void "test a bulk write with a null member in the in list"() {
        given:
        seed()

        when: 'the null member matches no row, like SQL'
        Number deleted = WriteOperationEntity.where { inList('name', ['a', null]) }.deleteAll()
        manager.session.clear()

        then:
        deleted == 1
        remainingNames() == ['b', 'c', 'd', 'e']
    }

    void "test a bulk write filters by an enum property"() {
        given:
        seed()

        when:
        Number deleted = WriteOperationEntity.where { eq('status', WriteStatus.ACTIVE) }.deleteAll()
        manager.session.clear()

        then: 'the null status row is not selected'
        deleted == 3
        remainingNames() == ['c', 'd']
    }

    void "test updateAll with a null value and an unknown property"() {
        given:
        seed()

        when: 'the set value is null'
        Number updated = WriteOperationEntity.where { eq('name', 'a') }.updateAll([name: null])
        manager.session.clear()

        then:
        updated == 1
        WriteOperationEntity.where { isNull('name') }.count() == 1

        when: 'the property does not exist'
        WriteOperationEntity.where { eq('name', 'b') }.updateAll([noSuchProperty: 1])

        then:
        InvalidDataAccessResourceUsageException e = thrown()
        e.message.contains('noSuchProperty')
    }

    void "test a bulk write with a negated association path"() {
        given:
        def (parentAId, parentBId) = seedParentsAndChildren()

        when:
        Number deleted = WriteOperationChild.where { not { eq('parent.id', parentAId) } }.deleteAll()
        manager.session.clear()

        then:
        deleted == 1
        WriteOperationChild.where {}.list()*.name == ['child-a']
    }

    void "test a bulk write with a lazy in subquery"() {
        given:
        def (parentAId, parentBId) = seedParentsAndChildren()

        when:
        Number updated = WriteOperationParent.where {
            inList('id', WriteOperationChild.whereLazy {
                projections { property('parent.id') }
                eq('name', 'child-a')
            })
        }.updateAll([name: 'matched'])
        manager.session.clear()

        then: 'the deferred subquery keeps its restriction'
        updated == 1
        WriteOperationParent.where {}.list()*.name.sort() == ['matched', 'p-b']
    }

    void "test a bulk write with a lazy exists subquery"() {
        given:
        def (parentAId, parentBId) = seedParentsAndChildren()

        when:
        Number updated = WriteOperationParent.where {
            exists(WriteOperationChild.whereLazy {
                eqProperty('parent.id', '{alias}.id')
                eq('name', 'child-a')
            })
        }.updateAll([name: 'matched'])
        manager.session.clear()

        then: 'the deferred subquery keeps its restriction'
        updated == 1
        WriteOperationParent.where {}.list()*.name.sort() == ['matched', 'p-b']
    }

    void "test a bulk write with a subquery that has no criteria"() {
        given:
        def (parentAId, parentBId) = seedParentsAndChildren()

        when: 'the subquery has no restriction, so it selects every parent'
        Number deleted = WriteOperationChild.where { inList('parent', WriteOperationParent.where {}) }.deleteAll()
        manager.session.clear()

        then: 'the subquery renders without a where clause'
        deleted == 2
        WriteOperationChild.where {}.list().isEmpty()
    }

    void "test a bulk write compares a property with a subquery"() {
        given:
        seed()

        when: 'rows above the average amount'
        Number deleted = WriteOperationEntity.where {
            gt('amount', WriteOperationEntity.where { projections { avg('amount') } })
        }.deleteAll()
        manager.session.clear()

        then: 'the average of the amounts is 2, so only the row above it is deleted'
        deleted == 1
        remainingNames() == ['a', 'b', 'd', 'e']
    }

    void "test a bulk write filters with a function call"() {
        given:
        seed()

        when:
        Number deleted = WriteOperationEntity.where { upper(name) == 'A' }.deleteAll()
        manager.session.clear()

        then:
        deleted == 1
        remainingNames() == ['b', 'c', 'd', 'e']
    }

    void "test a bulk write deletes the rows above the average amount"() {
        given:
        seed()

        when:
        Number deleted = WriteOperationEntity.where { amount > avg(amount) }.deleteAll()
        manager.session.clear()

        then: 'the average of 1, 2, 3 and 2 is 2, so only the row above it is deleted'
        deleted == 1
        remainingNames() == ['a', 'b', 'd', 'e']
    }

    void "test a bulk write deletes the rows above the average of a filtered subquery"() {
        given:
        seed()

        when:
        Number deleted = WriteOperationEntity.where { amount > avg(amount).of { name == 'a' } }.deleteAll()
        manager.session.clear()

        then: 'the average of the rows named a is 1'
        deleted == 3
        remainingNames() == ['a', 'd']
    }

    void "test a bulk write deletes the rows below every amount of a property subquery"() {
        given:
        seed()

        when:
        Number deleted = WriteOperationEntity.where { amount < property(amount).of { name == 'c' } }.deleteAll()
        manager.session.clear()

        then: 'the property subquery compares against every amount of the row named c'
        deleted == 3
        remainingNames() == ['c', 'd']
    }

    void "test a bulk write compares a property with another property"() {
        given:
        seed()

        when:
        Number deleted = WriteOperationEntity.where { amount > otherAmount }.deleteAll()
        manager.session.clear()

        then: 'the null amounts are not selected'
        deleted == 4
        remainingNames() == ['d']
    }

    void "test a bulk write filters with the length function"() {
        given:
        seed()

        when:
        Number deleted = WriteOperationEntity.where { length(name) == 1 }.deleteAll()
        manager.session.clear()

        then:
        deleted == 5
        remainingNames() == []
    }

    void "test a bulk update keeps the version of the row"() {
        given:
        new VersionedWriteEntity(name: 'versioned').save(flush: true)
        Long versionBefore = VersionedWriteEntity.where { eq('name', 'versioned') }.get().version

        when:
        Number updated = VersionedWriteEntity.where { eq('name', 'versioned') }.updateAll([name: 'renamed'])
        manager.session.clear()

        then: 'a bulk update does not increment the version, like Hibernate without the versioned keyword'
        updated == 1
        VersionedWriteEntity.where { eq('name', 'renamed') }.get().version == versionBefore
    }

    void "test a bulk write rejects the pagination it cannot honour"() {
        given:
        seed()

        when: 'a delete that asks for a limit'
        WriteOperationEntity.where {}.max(2).deleteAll()

        then: 'a bulk statement has no limit clause, so it fails instead of deleting every row'
        InvalidDataAccessResourceUsageException e = thrown()
        e.message.contains('maxResults')

        when: 'an update that asks for an offset'
        WriteOperationEntity.where {}.offset(1).updateAll([name: 'renamed'])

        then:
        InvalidDataAccessResourceUsageException e2 = thrown()
        e2.message.contains('offset')

        when: 'the same criteria without pagination still deletes every row'
        Number deleted = WriteOperationEntity.where {}.deleteAll()
        manager.session.clear()

        then:
        deleted == 5
    }

    void "test a bulk update sets and clears an association"() {
        given:
        def parentA = new WriteOperationParent(name: 'a').save(flush: true)
        def parentB = new WriteOperationParent(name: 'b').save(flush: true)
        new WriteOperationChild(name: 'c', parent: parentA).save(flush: true)

        when: 'the child moves to the other parent'
        Number updated = WriteOperationChild.where { eq('parent.id', parentA.id) }.updateAll([parent: parentB])
        manager.session.clear()
        def moved = WriteOperationChild.where { eq('parent.id', parentB.id) }.list()*.name

        then:
        updated == 1
        moved == ['c']

        when: 'the association is cleared'
        Number cleared = WriteOperationChild.where { eq('name', 'c') }.updateAll([parent: null])
        manager.session.clear()
        def orphans = WriteOperationChild.where { isNull('parent') }.list()*.name

        then:
        cleared == 1
        orphans == ['c']
    }

    void "test a bulk write does not mutate the criteria"() {
        given:
        seed()
        def criteria = WriteOperationEntity.where { eq('name', 'a') }

        when:
        Number deleted = criteria.deleteAll()
        manager.session.clear()

        then: 'the criteria keeps its structure and can be used again'
        deleted == 1
        criteria.projections.isEmpty()
        criteria.criteria.size() == 1
        criteria.list().isEmpty()
    }

    private static Closure associationIdCriteria(String operator, Long parentAId, Long parentBId) {
        switch (operator) {
            case 'in':
                return { inList('parent.id', [parentAId]) }
            case 'gt':
                return { gt('parent.id', parentAId) }
            case 'lt':
                return { lt('parent.id', parentAId) }
            case 'ge':
                return { ge('parent.id', parentAId) }
            case 'le':
                return { le('parent.id', parentAId) }
            case 'between':
                return { between('parent.id', parentAId, parentBId) }
            case 'ne':
                return { ne('parent.id', parentAId) }
            case 'isNull':
                return { isNull('parent.id') }
            default:
                throw new IllegalArgumentException("Unknown operator: ${operator}")
        }
    }

    private static List<Long> seedParentsAndChildren() {
        def parentA = new WriteOperationParent(name: 'p-a').save(flush: true)
        def parentB = new WriteOperationParent(name: 'p-b').save(flush: true)
        new WriteOperationChild(parent: parentA, name: 'child-a').save(flush: true)
        new WriteOperationChild(parent: parentB, name: 'child-b').save(flush: true)
        [parentA.id, parentB.id]
    }
}

@Entity
class WriteOperationEntity implements HibernateEntity<WriteOperationEntity> {

    String name
    Integer amount
    Integer otherAmount
    Boolean active
    WriteStatus status
}

enum WriteStatus {
    ACTIVE, INACTIVE
}

@Entity
class WriteOperationParent implements HibernateEntity<WriteOperationParent> {

    String name

    static hasMany = [children: WriteOperationChild]
}

@Entity
class WriteOperationChild implements HibernateEntity<WriteOperationChild> {

    String name

    static belongsTo = [parent: WriteOperationParent]
}

@Entity
class VersionedWriteEntity implements HibernateEntity<VersionedWriteEntity> {

    String name
    Long version
}
