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
import org.hibernate.annotations.Filter
import org.hibernate.annotations.FilterDef
import org.hibernate.annotations.Formula
import org.hibernate.annotations.SQLRestriction

/**
 * Criteria next to a formula property: the mapping DSL declares the derived column and a query
 * reads and orders by it like any other property.
 */
class CriteriaMappingFeaturesSpec extends HibernateGormDatastoreSpec {

    def setupSpec() {
        manager.registerDomainClasses(FormulaEntity, AnnotatedFormulaEntity, RestrictedEntity, FilteredEntity)
    }

    void "a criteria queries and orders by an annotated formula property"() {
        given:
        new AnnotatedFormulaEntity(name: 'a', amount: 1).save(flush: true)
        new AnnotatedFormulaEntity(name: 'b', amount: 2).save(flush: true)
        new AnnotatedFormulaEntity(name: 'c', amount: 3).save(flush: true)

        when:
        def doubled = AnnotatedFormulaEntity.where { doubled > 3 }.list()*.name.sort()
        def ordered = AnnotatedFormulaEntity.where {}.list(sort: 'doubled', order: 'desc')*.name
        manager.session.clear()
        def values = AnnotatedFormulaEntity.where {}.list().collect { [it.name, it.doubled] }

        then:
        doubled == ['b', 'c']
        ordered == ['c', 'b', 'a']
        values == [['a', 2], ['b', 4], ['c', 6]]
    }

    void "the sql restriction of an entity is part of every query"() {
        given:
        new RestrictedEntity(name: 'visible', active: true).save(flush: true)
        new RestrictedEntity(name: 'hidden', active: false).save(flush: true)

        when:
        def all = RestrictedEntity.where {}.list()*.name
        def count = RestrictedEntity.where {}.count()
        def hidden = RestrictedEntity.where { eq('name', 'hidden') }.list()

        then: 'the restriction is part of the criteria and of its count'
        all == ['visible']
        count == 1
        hidden.isEmpty()
    }

    void "the sql restriction of an entity is part of a bulk update"() {
        given:
        new RestrictedEntity(name: 'visible', active: true).save(flush: true)
        new RestrictedEntity(name: 'hidden', active: false).save(flush: true)

        when:
        Number updated = RestrictedEntity.where {}.updateAll([name: 'renamed'])
        manager.session.clear()

        then: 'only the rows the restriction allows are updated'
        updated == 1
        RestrictedEntity.where {}.list()*.name == ['renamed']
    }

    void "a criteria honours an enabled Hibernate filter"() {
        given:
        new FilteredEntity(name: 'visible', active: true).save(flush: true)
        new FilteredEntity(name: 'hidden', active: false).save(flush: true)

        when:
        def withoutFilter = FilteredEntity.where {}.list()*.name.sort()
        datastore.sessionFactory.currentSession.enableFilter('activeOnly')
        def withFilter = FilteredEntity.where {}.list()*.name
        datastore.sessionFactory.currentSession.disableFilter('activeOnly')
        def afterDisable = FilteredEntity.where {}.list()*.name.sort()

        then:
        withoutFilter == ['hidden', 'visible']
        withFilter == ['visible']
        afterDisable == ['hidden', 'visible']
    }

    void "a criteria queries and orders by a formula property"() {
        given:
        new FormulaEntity(name: 'a', amount: 1).save(flush: true)
        new FormulaEntity(name: 'b', amount: 2).save(flush: true)
        new FormulaEntity(name: 'c', amount: 3).save(flush: true)

        when:
        def doubled = FormulaEntity.where { doubled > 3 }.list()*.name.sort()
        def ordered = FormulaEntity.where {}.list(sort: 'doubled', order: 'desc')*.name
        manager.session.clear()
        def values = FormulaEntity.where {}.list().collect { [it.name, it.doubled] }

        then:
        doubled == ['b', 'c']
        ordered == ['c', 'b', 'a']
        values == [['a', 2], ['b', 4], ['c', 6]]
    }
}

@Entity
class FormulaEntity implements HibernateEntity<FormulaEntity> {

    String name
    Integer amount
    Integer doubled

    static mapping = {
        doubled formula: 'amount * 2'
    }
}

@Entity
class AnnotatedFormulaEntity implements HibernateEntity<AnnotatedFormulaEntity> {

    String name
    Integer amount

    @Formula('amount * 2')
    Integer doubled
}

@Entity
@SQLRestriction('active = true')
class RestrictedEntity implements HibernateEntity<RestrictedEntity> {

    String name
    Boolean active
}

@Entity
@FilterDef(name = 'activeOnly', defaultCondition = 'active = true')
@Filter(name = 'activeOnly')
class FilteredEntity implements HibernateEntity<FilteredEntity> {

    String name
    Boolean active
}
