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

import grails.gorm.annotation.Entity
import grails.gorm.hibernate.HibernateEntity
import grails.gorm.tests.HibernateGormDatastoreSpec

/**
 * The subquery and aggregate forms through the criteria builder, the other API the where syntax
 * covers in the sibling specs: the builder hands a closure or a criteria to eqAll/gtAll/inList and
 * to exists/notExists.
 */
class CriteriaBuilderSubquerySpec extends HibernateGormDatastoreSpec {

    def setupSpec() {
        manager.registerDomainClasses(BuilderParent, BuilderChild)
    }

    private void seed() {
        def first = new BuilderParent(name: 'a', amount: 1).save(failOnError: true)
        def second = new BuilderParent(name: 'b', amount: 5).save(failOnError: true)
        new BuilderParent(name: 'c', amount: 3).save(failOnError: true)
        new BuilderChild(name: 'c1', parent: first).save(failOnError: true)
        new BuilderChild(name: 'c2', parent: second).save(failOnError: true)
        manager.session.flush()
        manager.session.clear()
    }

    void "the builder composes an aggregate subquery"() {
        given:
        seed()

        when:
        def aboveAverage = BuilderParent.createCriteria().list {
            gt('amount', BuilderParent.where { projections { avg('amount') } })
        }*.name.sort()

        then: 'the average of 1, 5 and 3 is 3'
        aboveAverage == ['b']
    }

    void "the builder composes an all subquery from a closure"() {
        given:
        seed()

        when:
        def belowAll = BuilderParent.createCriteria().list {
            ltAll('amount', { eq('name', 'c') })
        }*.name.sort()

        then: 'every amount of the row named c is 3'
        belowAll == ['a']
    }

    void "the builder composes an in subquery from a closure"() {
        given:
        seed()

        when:
        def names = BuilderParent.createCriteria().list {
            inList('name', { eq('amount', 1) })
        }*.name

        then:
        names == ['a']
    }

    void "the builder composes exists and notExists with a correlated subquery"() {
        given:
        seed()

        when:
        def withChildren = BuilderParent.createCriteria().list {
            exists(BuilderChild.where { eqProperty('parent.id', '{alias}.id') })
        }*.name.sort()
        def withoutChildren = BuilderParent.createCriteria().list {
            notExists(BuilderChild.where { eqProperty('parent.id', '{alias}.id') })
        }*.name

        then:
        withChildren == ['a', 'b']
        withoutChildren == ['c']
    }

    void "findAllWhere matches an association by value"() {
        given:
        seed()
        def parent = BuilderParent.where { eq('name', 'a') }.get()

        when:
        def children = BuilderChild.findAllWhere([parent: parent])*.name

        then:
        children == ['c1']
    }
}

@Entity
class BuilderParent implements HibernateEntity<BuilderParent> {

    String name
    Integer amount

    static hasMany = [children: BuilderChild]
}

@Entity
class BuilderChild implements HibernateEntity<BuilderChild> {

    String name

    static belongsTo = [parent: BuilderParent]
}
