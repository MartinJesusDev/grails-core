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
package grails.gorm.tests

import grails.gorm.annotation.Entity
import grails.neo4j.Neo4jEntity
import spock.lang.PendingFeature
import org.apache.grails.data.neo4j.core.Neo4jGormDatastoreSpec

/**
 * The aggregate subquery forms the where query manual documents, the same ones the Hibernate
 * implementation covers, to see whether the Neo4j implementation supports them.
 */
class AggregateSubquerySpec extends Neo4jGormDatastoreSpec {

    void setupSpec() {
        manager.registerDomainClasses(Neo4jAggregateItem)
    }

    private void seed() {
        new Neo4jAggregateItem(name: 'a', amount: 1).save(flush: true)
        new Neo4jAggregateItem(name: 'b', amount: 2).save(flush: true)
        new Neo4jAggregateItem(name: 'c', amount: 3).save(flush: true)
        manager.session.flush()
    }

    @PendingFeature(reason = 'The Neo4j implementation does not translate the aggregate subquery forms of the where query manual yet; see the issue that tracks the transform and the translation for the non-SQL datastores')
    void 'a property is compared with an aggregate subquery'() {
        given:
        seed()

        expect: 'the average of 1, 2 and 3 is 2'
        Neo4jAggregateItem.where { amount > avg(amount) }.list()*.name == ['c']
    }

    @PendingFeature(reason = 'The Neo4j implementation does not translate the aggregate subquery forms of the where query manual yet; see the issue that tracks the transform and the translation for the non-SQL datastores')
    void 'an aggregate subquery is filtered by a closure'() {
        given:
        seed()

        expect: 'the average of the rows named a is 1'
        Neo4jAggregateItem.where { amount > avg(amount).of { name == 'a' } }.list()*.name.sort() == ['b', 'c']
    }

    @PendingFeature(reason = 'The Neo4j implementation does not translate the aggregate subquery forms of the where query manual yet; see the issue that tracks the transform and the translation for the non-SQL datastores')
    void 'a property subquery compares against every value'() {
        given:
        seed()

        expect:
        Neo4jAggregateItem.where { amount < property(amount).of { name == 'c' } }.list()*.name.sort() == ['a', 'b']
    }

    @PendingFeature(reason = 'The Neo4j implementation does not translate the aggregate subquery forms of the where query manual yet; see the issue that tracks the transform and the translation for the non-SQL datastores')
    void 'a where query built from another where query supplies the aggregate'() {
        given:
        seed()

        expect: 'the average of the rows with an amount above zero is 2'
        Neo4jAggregateItem.where { amount > where { amount > 0 }.avg('amount') }.list()*.name == ['c']
    }
}

@Entity
class Neo4jAggregateItem implements Neo4jEntity<Neo4jAggregateItem> {

    String name
    Integer amount
}
