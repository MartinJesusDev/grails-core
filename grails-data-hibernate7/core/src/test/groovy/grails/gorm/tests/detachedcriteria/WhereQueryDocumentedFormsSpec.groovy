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
import org.grails.datastore.mapping.core.exceptions.ConfigurationException
import org.grails.datastore.mapping.query.Query
import org.grails.datastore.mapping.query.api.AssociationCriteria

/**
 * Executes the forms the GORM manual documents for where queries and detached criteria: the
 * operator set, the size operator, alias variables that cross-reference an exists subquery, the
 * function calls the transformer supports, whereAny, chained where calls, and the comparison with
 * a projection subquery (gt with a DetachedCriteria that projects avg).
 *
 * Every criteria is built inside a when block: a where query written directly in an expect block
 * does not receive the GORM transformation, because Spock wraps the expression.
 */
class WhereQueryDocumentedFormsSpec extends HibernateGormDatastoreSpec {

    def setupSpec() {
        manager.registerDomainClasses(DocFormEntity, DocFormParent, DocFormChild)
    }

    private void seedEntities() {
        new DocFormEntity(name: 'a', amount: 1, otherAmount: 0, created: date(2011, 1, 1)).save(flush: true)
        new DocFormEntity(name: 'b', amount: 2, otherAmount: 1, created: date(2012, 1, 1)).save(flush: true)
        new DocFormEntity(name: 'c', amount: 3, otherAmount: 2, created: date(2013, 1, 1)).save(flush: true)
        new DocFormEntity(name: 'd', amount: null, otherAmount: null, created: null).save(flush: true)
    }

    private static Date date(int year, int month, int day) {
        new GregorianCalendar(year, month - 1, day).time
    }

    void "the documented where query operators execute"() {
        given:
        seedEntities()

        when:
        def eqResult = DocFormEntity.where { name == 'a' }.list()*.name
        def neResult = DocFormEntity.where { name != 'a' }.list()*.name.sort()
        def gtResult = DocFormEntity.where { amount > 1 }.list()*.name.sort()
        def ltResult = DocFormEntity.where { amount < 3 }.list()*.name.sort()
        def geResult = DocFormEntity.where { amount >= 2 }.list()*.name.sort()
        def leResult = DocFormEntity.where { amount <= 2 }.list()*.name.sort()
        def likeResult = DocFormEntity.where { name ==~ 'a%' }.list()*.name
        def ilikeResult = DocFormEntity.where { name =~ 'A%' }.list()*.name
        def inResult = DocFormEntity.where { name in ['a', 'b'] }.list()*.name.sort()
        def andResult = DocFormEntity.where { amount > 0 && amount < 3 }.list()*.name.sort()
        def orResult = DocFormEntity.where { name == 'a' || name == 'c' }.list()*.name.sort()
        def notResult = DocFormEntity.where { !(name == 'a') }.list()*.name.sort()
        def nullResult = DocFormEntity.where { amount == null }.list()*.name

        then:
        eqResult == ['a']
        neResult == ['b', 'c', 'd']
        gtResult == ['b', 'c']
        ltResult == ['a', 'b']
        geResult == ['b', 'c']
        leResult == ['a', 'b']
        likeResult == ['a']
        ilikeResult == ['a']
        inResult == ['a', 'b']
        andResult == ['a', 'b']
        orResult == ['a', 'c']
        notResult == ['b', 'c', 'd']
        nullResult == ['d']
    }

    void "the documented size operator executes"() {
        given:
        new DocFormParent(name: 'two-children')
                .addToChildren(name: 'first')
                .addToChildren(name: 'second')
                .save(flush: true)
        new DocFormParent(name: 'none').save(flush: true)

        when:
        def eqResult = DocFormParent.where { children.size() == 2 }.list()*.name
        def empty = DocFormParent.where { children.size() == 0 }.list()*.name
        def gtResult = DocFormParent.where { children.size() > 0 }.list()*.name

        then:
        eqResult == ['two-children']
        empty == ['none']
        gtResult == ['two-children']
    }

    void "the documented alias variables cross-reference an exists subquery"() {
        given:
        new DocFormParent(name: 'with-child').addToChildren(name: 'child').save(flush: true)
        new DocFormParent(name: 'without-child').save(flush: true)

        when:
        def query = DocFormParent.where {
            def p1 = DocFormParent
            exists DocFormChild.where {
                def c1 = DocFormChild
                def p2 = parent
                return p2.id == p1.id
            }.id()
        }
        def results = query.list()*.name

        then:
        results == ['with-child']
    }

    void "the documented where query functions execute"() {
        given:
        seedEntities()

        when:
        def upperResult = DocFormEntity.where { upper(name) == 'A' }.list()*.name
        def lowerResult = DocFormEntity.where { lower(name) == 'b' }.list()*.name

        then:
        upperResult == ['a']
        lowerResult == ['b']
    }

    void "whereAny builds a disjunction at the root"() {
        given:
        seedEntities()

        when:
        def results = DocFormEntity.whereAny { name == 'a'; name == 'c' }.list()*.name.sort()

        then:
        results == ['a', 'c']
    }

    void "the documented dynamic query building with chained where calls"() {
        given:
        seedEntities()
        def buildQuery = { Map filters ->
            def query = DocFormEntity.where {}
            if (filters.containsKey('name')) {
                query = query.where { name == filters.name }
            }
            if (filters.containsKey('amount')) {
                query = query.where { amount > filters.amount }
            }
            query
        }

        when:
        def byName = buildQuery([name: 'b']).list()*.name
        def byAmount = buildQuery([amount: 1]).list()*.name.sort()
        def both = buildQuery([name: 'c', amount: 2]).list()*.name

        then:
        byName == ['b']
        byAmount == ['b', 'c']
        both == ['c']
    }

    void "the documented comparison with a projection subquery"() {
        given:
        seedEntities()

        when: 'the rows above the average amount, as the manual shows with gt and a projection'
        def aboveAverage = DocFormEntity.where {
            gt('amount', DocFormEntity.where { projections { avg('amount') } })
        }.list()*.name.sort()

        then:
        aboveAverage == ['c']
    }

    void "diagnostic: the and operator builds a conjunction"() {
        when:
        def criteria = DocFormEntity.where { amount > 0 && amount < 3 }

        then:
        criteria.criteria.size() == 1
        criteria.criteria[0] instanceof Query.Junction
    }

    void "diagnostic: the and operator after another where closure in the same method"() {
        given:
        seedEntities()

        when:
        def first = DocFormEntity.where { name == 'a' }.list()*.name
        def second = DocFormEntity.where { amount > 0 && amount < 3 }.list()*.name.sort()

        then:
        first == ['a']
        second == ['a', 'b']
    }

    void "diagnostic: and after the like operator in the same method"() {
        given:
        seedEntities()

        when:
        def likeResult = DocFormEntity.where { name ==~ 'a%' }.list()*.name
        def andResult = DocFormEntity.where { amount > 0 && amount < 3 }.list()*.name.sort()

        then:
        likeResult == ['a']
        andResult == ['a', 'b']
    }

    void "diagnostic: and after the in operator in the same method"() {
        given:
        seedEntities()

        when:
        def inResult = DocFormEntity.where { name in ['a', 'b'] }.list()*.name.sort()
        def andResult = DocFormEntity.where { amount > 0 && amount < 3 }.list()*.name.sort()

        then:
        inResult == ['a', 'b']
        andResult == ['a', 'b']
    }

    void "diagnostic: the operators test sequence up to and"() {
        given:
        seedEntities()

        when:
        def eqResult = DocFormEntity.where { name == 'a' }.list()*.name
        def neResult = DocFormEntity.where { name != 'a' }.list()*.name.sort()
        def gtResult = DocFormEntity.where { amount > 1 }.list()*.name.sort()
        def ltResult = DocFormEntity.where { amount < 3 }.list()*.name.sort()
        def geResult = DocFormEntity.where { amount >= 2 }.list()*.name.sort()
        def leResult = DocFormEntity.where { amount <= 2 }.list()*.name.sort()
        def likeResult = DocFormEntity.where { name ==~ 'a%' }.list()*.name
        def ilikeResult = DocFormEntity.where { name =~ 'A%' }.list()*.name
        def inResult = DocFormEntity.where { name in ['a', 'b'] }.list()*.name.sort()
        def andResult = DocFormEntity.where { amount > 0 && amount < 3 }.list()*.name.sort()

        then:
        eqResult == ['a']
        neResult == ['b', 'c', 'd']
        gtResult == ['b', 'c']
        ltResult == ['a', 'b']
        geResult == ['b', 'c']
        leResult == ['a', 'b']
        likeResult == ['a']
        ilikeResult == ['a']
        inResult == ['a', 'b']
        andResult == ['a', 'b']
    }

    void "an unresolved property path in a comparison fails with a clear message"() {
        when:
        DocFormEntity.where { eqProperty('name', 'missing.name') }.list()

        then:
        ConfigurationException e = thrown()
        e.message.contains('missing.name')
    }

    void "the documented aggregate subqueries execute"() {
        given:
        seedEntities()

        when:
        def aboveAverage = DocFormEntity.where { amount > avg(amount) }.list()*.name.sort()
        def aboveAverageOf = DocFormEntity.where { amount > avg(amount).of { name == 'a' } }.list()*.name.sort()
        def belowPropertyOf = DocFormEntity.where { amount < property(amount).of { name == 'c' } }.list()*.name.sort()
        def aboveAverageOfWhere = DocFormEntity.where { amount > where { amount > 0 }.avg('amount') }.list()*.name.sort()

        then:
        aboveAverage == ['c']
        aboveAverageOf == ['b', 'c']
        belowPropertyOf == ['a', 'b']
        aboveAverageOfWhere == ['c']
    }

    void "the documented date function executes"() {
        given:
        seedEntities()

        when:
        def bornIn2011 = DocFormEntity.where { year(created) == 2011 }.list()*.name
        def bornAfter2011 = DocFormEntity.where { year(created) > 2011 }.list()*.name.sort()

        then:
        bornIn2011 == ['a']
        bornAfter2011 == ['b', 'c']
    }

    void "the documented string functions execute"() {
        given:
        seedEntities()

        when:
        def trimmed = DocFormEntity.where { trim(name) == 'a' }.list()*.name
        def oneCharacter = DocFormEntity.where { length(name) == 1 }.list()*.name.sort()

        then:
        trimmed == ['a']
        oneCharacter == ['a', 'b', 'c', 'd']
    }

    void "the documented property comparisons execute"() {
        given:
        seedEntities()

        when:
        def greater = DocFormEntity.where { amount > otherAmount }.list()*.name.sort()
        def equal = DocFormEntity.where { amount == otherAmount }.list()*.name
        def greaterOrEqual = DocFormEntity.where { amount >= otherAmount }.list()*.name.sort()
        def notEqual = DocFormEntity.where { amount != otherAmount }.list()*.name.sort()

        then:
        greater == ['a', 'b', 'c']
        equal == []
        greaterOrEqual == ['a', 'b', 'c']
        notEqual == ['a', 'b', 'c']
    }

    void "the alias exists form keeps the comparison criterion in the subquery tree"() {
        when:
        def query = DocFormParent.where {
            def p1 = DocFormParent
            exists DocFormChild.where {
                def c1 = DocFormChild
                def p2 = parent
                return p2.id == p1.id
            }.id()
        }

        then: 'the subquery holds the comparison inside the association block the alias declares'
        query.criteria.size() == 1
        Query.Exists exists = (Query.Exists) query.criteria[0]
        List<Query.Criterion> subqueryCriteria = exists.subquery.criteria
        subqueryCriteria.any { it instanceof AssociationCriteria }
        subqueryCriteria.findAll { it instanceof AssociationCriteria }
                .any { AssociationCriteria ac -> ac.criteria.any { it instanceof Query.EqualsProperty } }
    }
}

@Entity
class DocFormEntity implements HibernateEntity<DocFormEntity> {

    String name
    Integer amount
    Integer otherAmount
    Date created
}

@Entity
class DocFormParent implements HibernateEntity<DocFormParent> {

    String name

    static hasMany = [children: DocFormChild]
}

@Entity
class DocFormChild implements HibernateEntity<DocFormChild> {

    String name

    static belongsTo = [parent: DocFormParent]
}
