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
package grails.gorm.tests.multitenancy

import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.datastore.mapping.multitenancy.MultiTenancySettings
import org.grails.datastore.mapping.multitenancy.resolvers.SystemPropertyTenantResolver
import org.grails.orm.hibernate.HibernateDatastore
import org.hibernate.dialect.H2Dialect
import spock.lang.AutoCleanup
import spock.lang.Specification
import spock.util.environment.RestoreSystemProperties

import grails.gorm.MultiTenant
import grails.gorm.annotation.Entity

/**
 * The tenant discriminator must scope every part of a criteria query: the outer entity, the
 * subquery, and the bulk writes that the criteria selects. A subquery that ignores the tenant
 * would let one tenant's rows decide what another tenant sees, and a bulk write without the
 * discriminator would touch every tenant.
 */
@RestoreSystemProperties
class MultiTenancySubquerySpec extends Specification {

    @AutoCleanup
    HibernateDatastore datastore

    void "test criteria subqueries and bulk writes stay inside the current tenant"() {
        given: 'a discriminator based multi-tenancy configuration'
        System.setProperty(SystemPropertyTenantResolver.PROPERTY_NAME, "")
        Map config = [
                "grails.gorm.multiTenancy.mode"               : MultiTenancySettings.MultiTenancyMode.DISCRIMINATOR,
                "grails.gorm.multiTenancy.tenantResolverClass": SystemPropertyTenantResolver.name,
                'dataSource.url'                              : "jdbc:h2:mem:tenantSubqueryDB;LOCK_TIMEOUT=10000",
                'dataSource.dialect'                          : H2Dialect.name,
                'dataSource.formatSql'                        : 'true',
                'hibernate.flush.mode'                        : 'COMMIT',
                'hibernate.cache.queries'                     : 'false',
                'hibernate.cache.use_query_cache'             : 'false',
                'hibernate.hbm2ddl.auto'                      : 'create',
        ]
        datastore = new HibernateDatastore(DatastoreUtils.createPropertyResolver(config), getClass().getPackage())

        when: 'two tenants store their rows'
        System.setProperty(SystemPropertyTenantResolver.PROPERTY_NAME, 'ford')
        TenantItem.withTransaction {
            new TenantItem(name: 'a', amount: 1).save(flush: true)
            new TenantItem(name: 'b', amount: 2).save(flush: true)
        }
        System.setProperty(SystemPropertyTenantResolver.PROPERTY_NAME, 'tesla')
        TenantItem.withNewSession { it.clear() }
        TenantItem.withTransaction {
            new TenantItem(name: 'c', amount: 3).save(flush: true)
        }

        and: 'the current tenant is ford again'
        System.setProperty(SystemPropertyTenantResolver.PROPERTY_NAME, 'ford')
        TenantItem.withNewSession { it.clear() }

        then: 'the outer query only sees the current tenant'
        TenantItem.withTransaction { TenantItem.where {}.list()*.name.sort() } == ['a', 'b']

        when: 'a subquery only matches a row of another tenant'
        TenantItem.withNewSession { it.clear() }
        def leaked = TenantItem.withTransaction {
            TenantItem.where { exists(TenantItem.where { eq('name', 'c') }) }.list()
        }

        then: 'the subquery is scoped to the current tenant and matches nothing'
        leaked.isEmpty()

        when: 'a subquery over the current tenant is used'
        TenantItem.withNewSession { it.clear() }
        def matching = TenantItem.withTransaction {
            TenantItem.where { gtSome('amount', TenantItem.where { eq('name', 'a') }) }.list()*.name
        }

        then:
        matching == ['b']

        when: 'a bulk delete selects its rows with a subquery'
        TenantItem.withNewSession { it.clear() }
        def deleted = TenantItem.withTransaction {
            TenantItem.where {
                inList('amount', TenantItem.where {
                    projections { property('amount') }
                    eq('name', 'a')
                })
            }.deleteAll()
        }

        then: 'only the current tenant is affected'
        deleted == 1
        TenantItem.withTransaction { TenantItem.where {}.list()*.name } == ['b']

        when: 'the other tenant changes'
        System.setProperty(SystemPropertyTenantResolver.PROPERTY_NAME, 'tesla')
        TenantItem.withNewSession { it.clear() }

        then: 'the other tenant keeps its row'
        TenantItem.withTransaction { TenantItem.where {}.list()*.name } == ['c']

        cleanup:
        System.setProperty(SystemPropertyTenantResolver.PROPERTY_NAME, "")
    }
}

@Entity
class TenantItem implements MultiTenant<TenantItem> {

    String name
    Integer amount
    String tenant

    static mapping = {
        tenantId name: 'tenant'
    }
}
