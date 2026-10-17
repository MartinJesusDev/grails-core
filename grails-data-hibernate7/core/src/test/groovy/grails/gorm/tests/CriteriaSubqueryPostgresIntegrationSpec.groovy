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

import java.sql.Connection

import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.spock.Testcontainers
import spock.lang.Requires
import spock.lang.Shared

import grails.gorm.DetachedCriteria
import grails.gorm.annotation.Entity
import grails.gorm.hibernate.HibernateEntity

/**
 * Runs the comparison subquery matrix against a real PostgreSQL server and compares every result
 * with the same query written in SQL, executed on the transaction's own connection. The oracle
 * states the semantics independently of GORM's translator: an empty subquery is vacuously true
 * for ALL, a NULL member fails ALL and is ignored by SOME, duplicates do not change the result,
 * and a deferred whereLazy subquery keeps its restriction.
 */
@Testcontainers
@Requires({ isDockerAvailable() })
class CriteriaSubqueryPostgresIntegrationSpec extends HibernateGormDatastoreSpec {

    @Shared PostgreSQLContainer postgres = new PostgreSQLContainer('postgres:16')

    void setupSpec() {
        manager.registerDomainClasses(PostgresSubqueryEntity)
    }

    void "comparison subqueries match the SQL semantics on #db"() {
        given:
        if (!container.isRunning()) {
            container.start()
        }
        manager.destroy()
        manager.grailsConfig = [
                'dataSource.url'            : container.jdbcUrl,
                'dataSource.driverClassName': container.driverClassName,
                'dataSource.username'       : container.username,
                'dataSource.password'       : container.password,
                'dataSource.dbCreate'       : 'create-drop',
                'hibernate.show_sql'        : 'true',
        ]
        manager.setup(this.class)
        seed()

        expect: 'gtAll over an empty subquery is vacuously true'
        PostgresSubqueryEntity.where { gtAll('amount', valuesOf('no-such-name')) }.list()*.name.sort() == ['a', 'b', 'c', 'd', 'e']
        PostgresSubqueryEntity.where { gtAll('amount', valuesOf('no-such-name')) }.list()*.name.sort() ==
                oracle('select name from postgres_subquery_entity where amount > all (select amount from postgres_subquery_entity where name = ?)', ['no-such-name']).sort()

        and: 'a NULL member makes ALL fail and is ignored by SOME'
        PostgresSubqueryEntity.where { gtAll('amount', valuesOf('a', 'd')) }.list().isEmpty()
        oracle('select name from postgres_subquery_entity where amount > all (select amount from postgres_subquery_entity where name in (?, ?))', ['a', 'd']).isEmpty()
        PostgresSubqueryEntity.where { gtSome('amount', valuesOf('a', 'd')) }.list()*.name.sort() == ['b', 'c', 'e']
        PostgresSubqueryEntity.where { gtSome('amount', valuesOf('a', 'd')) }.list()*.name.sort() ==
                oracle('select name from postgres_subquery_entity where amount > some (select amount from postgres_subquery_entity where name in (?, ?))', ['a', 'd']).sort()

        and: 'duplicate members do not change gtAll'
        PostgresSubqueryEntity.where { gtAll('amount', valuesOf('b', 'e')) }.list()*.name == ['c']
        PostgresSubqueryEntity.where { gtAll('amount', valuesOf('b', 'e')) }.list()*.name ==
                oracle('select name from postgres_subquery_entity where amount > all (select amount from postgres_subquery_entity where name in (?, ?))', ['b', 'e'])

        and: 'a deferred whereLazy subquery keeps its restriction'
        PostgresSubqueryEntity.where { inList('name', PostgresSubqueryEntity.whereLazy { projections { property('name') }; eq('amount', 1) }) }.list()*.name == ['a']
        PostgresSubqueryEntity.where { inList('name', PostgresSubqueryEntity.whereLazy { projections { property('name') }; eq('amount', 1) }) }.list()*.name ==
                oracle('select name from postgres_subquery_entity where name in (select name from postgres_subquery_entity where amount = ?)', [1])

        and: 'executing a query does not mutate the subquery criteria'
        DetachedCriteria<PostgresSubqueryEntity> subquery = PostgresSubqueryEntity.where { eq('name', 'a') }
        PostgresSubqueryEntity.where { inList('amount', subquery) }.list()
        subquery.projections.isEmpty()
        subquery.list().first() instanceof PostgresSubqueryEntity

        where:
        db         | container
        'postgres' | postgres
    }

    private void seed() {
        new PostgresSubqueryEntity(name: 'a', amount: 1).save(flush: true)
        new PostgresSubqueryEntity(name: 'b', amount: 2).save(flush: true)
        new PostgresSubqueryEntity(name: 'c', amount: 3).save(flush: true)
        new PostgresSubqueryEntity(name: 'd', amount: null).save(flush: true)
        new PostgresSubqueryEntity(name: 'e', amount: 2).save(flush: true)
    }

    private static DetachedCriteria<PostgresSubqueryEntity> valuesOf(String... names) {
        PostgresSubqueryEntity.where {
            projections { property('amount') }
            inList('name', names as List)
        }
    }

    /**
     * Runs the SQL oracle on the transaction's own connection, so it sees the rows this test saved
     * before they are committed.
     */
    private List<String> oracle(String sql, List<Object> parameters = []) {
        List<String> names = []
        datastore.sessionFactory.currentSession.doWork { Connection connection ->
            connection.prepareStatement(sql).withCloseable { statement ->
                parameters.eachWithIndex { Object value, int index -> statement.setObject(index + 1, value) }
                statement.executeQuery().withCloseable { resultSet ->
                    while (resultSet.next()) {
                        names << resultSet.getString(1)
                    }
                }
            }
        }
        names
    }
}

@Entity
class PostgresSubqueryEntity implements HibernateEntity<PostgresSubqueryEntity> {

    String name
    Integer amount
}
