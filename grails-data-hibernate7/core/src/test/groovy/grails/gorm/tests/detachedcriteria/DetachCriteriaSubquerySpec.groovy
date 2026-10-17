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
import spock.lang.Ignore

@SuppressWarnings("GrMethodMayBeStatic")
class DetachCriteriaSubquerySpec extends HibernateGormDatastoreSpec {

    def setupSpec() {
        manager.registerDomainClasses(User, Group, GroupAssignment, Organisation)
    }

    void "test detached associated criteria in subquery"() {

        setup:
        User supVisor = createUser('supervisor@company.com')
        User user1 = createUser('user1@company.com')
        User user2 = createUser('user2@company.com')

        Group group1 = createGroup('Group 1', supVisor)
        Group group2 = createGroup('Group 2', supVisor)

        assignGroup(user1, group1)
        assignGroup(user1, group2)

        when:
        String supervisorEmail = 'supervisor@company.com'
        DetachedCriteria<User> criteria = User.where {
            exists(
                    GroupAssignment.where {
                        eqProperty "user.id", "{alias}.id"
                        group.supervisor.email == supervisorEmail
                    }.id()
            )
        }
        List<User> result = criteria.list()

        then:
        noExceptionThrown()
        result.size() == 1
    }

    void "test executing detached criteria in sub-query multiple times"() {

        setup:
        Organisation orgA = new Organisation(name: "A")
        orgA.addToUsers(email: 'user1@a')
        orgA.addToUsers(email: 'user2@a')
        orgA.addToUsers(email: 'user3@a')
        orgA.save(flush: true)
        Organisation orgB = new Organisation(name: "B")
        orgB.addToUsers(email: 'user1@b')
        orgB.addToUsers(email: 'user2@b')
        orgB.save(flush: true)

        when:
        def orgDetachedCritera = Organisation.where { name == 'A' || name == 'B' }
        def organisations = orgDetachedCritera.list()
        DetachedCriteria<User> criteria = User.where { inList('organisation', orgDetachedCritera) }
        List<User> result = criteria.list()
        result = criteria.list()

        then:
        result.size() == 5
    }

    void "test that detached criteria subquery should create implicit alias instead of using this_"() {

        setup:
        User supVisor = createUser('supervisor@company.com')
        User user1 = createUser('user1@company.com')
        User user2 = createUser('user2@company.com')

        Group group1 = createGroup('Group 1', supVisor)
        Group group2 = createGroup('Group 2', supVisor)

        assignGroup(user1, group1)
        assignGroup(user1, group2)

        when:
        String supervisorEmail = 'supervisor@company.com'
        DetachedCriteria<User> criteria = User.where {
            exists(
                    GroupAssignment.where {
                        eqProperty "user.id", "{alias}.id"
                        group.supervisor.email == supervisorEmail
                    }.id()
            )
        }
        List<User> result = criteria.list()

        then:
        noExceptionThrown()
        result.size() == 1
    }

    void "test a lazy subquery keeps its restriction when used with inList"() {
        setup:
        new Organisation(name: 'A').save(flush: true)
        new Organisation(name: 'B').save(flush: true)

        when:
        DetachedCriteria<Organisation> subquery = Organisation.whereLazy { eq('name', 'A') }
        List<Organisation> result = Organisation.where { inList('name', subquery) }.list()

        then:
        result*.name == ['A']
    }

    void "test a lazy subquery keeps its restriction when used with gtAll"() {
        setup:
        new Organisation(name: 'A').save(flush: true)
        new Organisation(name: 'B').save(flush: true)

        when:
        DetachedCriteria<Organisation> subquery = Organisation.whereLazy { eq('name', 'A') }
        List<Organisation> result = Organisation.where { gtAll('name', subquery) }.list()

        then:
        result*.name == ['B']
    }

    void "test a buildLazy subquery keeps its restriction when used with notIn"() {
        setup:
        new Organisation(name: 'A').save(flush: true)
        new Organisation(name: 'B').save(flush: true)

        when:
        DetachedCriteria<Organisation> subquery = new DetachedCriteria<>(Organisation).buildLazy { eq('name', 'A') }
        List<Organisation> result = Organisation.where { notIn('name', subquery) }.list()

        then:
        result*.name == ['B']
    }

    void "test a lazy detached criteria used as the root query still materializes"() {
        setup:
        new Organisation(name: 'A').save(flush: true)
        new Organisation(name: 'B').save(flush: true)

        expect:
        Organisation.whereLazy { eq('name', 'A') }.list()*.name == ['A']
    }

    void "test a lazy root query with the where query syntax"() {
        setup:
        new Organisation(name: 'A').save(flush: true)
        new Organisation(name: 'B').save(flush: true)

        when:
        DetachedCriteria<Organisation> query = Organisation.whereLazy { name == 'A' }
        List<Organisation> result = query.list()

        then:
        result*.name == ['A']
    }

    void "test a hoisted eager subquery keeps its restriction when used with inList"() {
        setup:
        new Organisation(name: 'A').save(flush: true)
        new Organisation(name: 'B').save(flush: true)

        when:
        DetachedCriteria<Organisation> subquery = Organisation.where { eq('name', 'A') }
        List<Organisation> result = Organisation.where { inList('name', subquery) }.list()

        then:
        result*.name == ['A']
    }

    void "test executing a query does not mutate the subquery criteria"() {
        setup:
        new Organisation(name: 'A').save(flush: true)
        new Organisation(name: 'B').save(flush: true)
        DetachedCriteria<Organisation> subquery = Organisation.where { eq('name', 'A') }

        when:
        Organisation.where { inList('name', subquery) }.list()

        then: 'the subquery keeps its own criteria and no projection is added to it'
        subquery.criteria.size() == 1
        subquery.projections.isEmpty()
    }

    void "test a subquery criteria can be reused for a different property"() {
        setup:
        new Organisation(name: 'A').save(flush: true)
        new Organisation(name: 'B').save(flush: true)

        when:
        DetachedCriteria<Organisation> subquery = Organisation.where { eq('name', 'A') }
        Organisation.where { inList('name', subquery) }.list()
        List<Organisation> result = Organisation.where { eq('id', subquery) }.list()

        then:
        result*.name == ['A']
    }

    void "test a criteria used as a subquery still returns entities when executed on its own"() {
        setup:
        new Organisation(name: 'A').save(flush: true)
        new Organisation(name: 'B').save(flush: true)
        DetachedCriteria<Organisation> subquery = Organisation.where { eq('name', 'A') }

        when:
        Organisation.where { gtAll('name', subquery) }.list()
        List<Organisation> result = subquery.list()

        then:
        result.size() == 1
        result[0] instanceof Organisation
    }

    void "test a not-equals subquery keeps its restriction and is not mutated"() {
        setup:
        new Organisation(name: 'A').save(flush: true)
        new Organisation(name: 'B').save(flush: true)

        when:
        DetachedCriteria<Organisation> subquery = Organisation.where { eq('name', 'A') }
        List<Organisation> result = Organisation.where { ne('name', subquery) }.list()

        then: 'ne excludes the subquery value, and null values are kept'
        result*.name == ['B']

        and: 'the subquery is not mutated'
        subquery.projections.isEmpty()
    }

    void "test a not-equals subquery materializes a lazy criteria"() {
        setup:
        new Organisation(name: 'A').save(flush: true)
        new Organisation(name: 'B').save(flush: true)

        when:
        DetachedCriteria<Organisation> subquery = Organisation.whereLazy { eq('name', 'A') }
        List<Organisation> result = Organisation.where { ne('name', subquery) }.list()

        then: 'the lazy subquery keeps its restriction'
        result*.name == ['B']
    }

    void "test a cross-entity subquery selects rows through an association"() {
        setup:
        Organisation orgA = new Organisation(name: 'A')
        orgA.addToUsers(email: 'u1@a')
        orgA.save(flush: true)
        Organisation orgB = new Organisation(name: 'B')
        orgB.addToUsers(email: 'u2@b')
        orgB.save(flush: true)

        when:
        List<User> users = User.where {
            inList('organisation', Organisation.where { eq('name', 'A') })
        }.list()

        then:
        users*.email == ['u1@a']
    }

    private User createUser(String email) {
        User user = new User(email: email)
        Organisation defaultOrg = Organisation.findOrCreateByName("default")
        defaultOrg.addToUsers(user)
        defaultOrg.save(flush: true)
        user
    }

    private Group createGroup(String name, User supervisor) {
        Group group = new Group()
        group.name = name
        group.supervisor = supervisor
        group.save(flush: true)
    }

    private void assignGroup(User user, Group group) {
        GroupAssignment groupAssignment = new GroupAssignment()
        groupAssignment.user = user
        groupAssignment.group = group
        groupAssignment.save(flush: true)
    }

}


@Entity
class User implements HibernateEntity<User> {
    String email
    static belongsTo = [organisation: Organisation]
    static mapping = {
        table 'T_USER'
    }
}

@Entity
class Group implements HibernateEntity<Group> {
    String name
    User supervisor
    static mapping = {
        table 'T_GROUP'
    }
}

@Entity
class GroupAssignment implements HibernateEntity<GroupAssignment> {
    User user
    Group group
    static mapping = {
        table 'T_GROUP_ASSIGNMENT'
    }
}

@Entity
class Organisation implements HibernateEntity<Organisation> {
    String name
    static hasMany = [users: User]
}
