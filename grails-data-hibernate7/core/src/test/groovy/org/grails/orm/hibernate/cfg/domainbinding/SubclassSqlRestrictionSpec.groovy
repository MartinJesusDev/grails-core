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
package org.grails.orm.hibernate.cfg.domainbinding

import grails.gorm.annotation.Entity
import org.grails.orm.hibernate.HibernateDatastore
import org.hibernate.annotations.SQLRestriction
import spock.lang.Specification

/**
 * Hibernate 7 maps a SQL restriction on the root class of the hierarchy and a subclass has no setter,
 * so the annotation on a subclass cannot be applied. The binder fails instead of dropping it.
 */
class SubclassSqlRestrictionSpec extends Specification {

    void "a SQL restriction declared on a subclass fails with a clear message"() {
        when: 'the mapping context maps the hierarchy'
        new HibernateDatastore(RestrictionRootEntity, RestrictedChildEntity)

        then: 'the binder cannot apply the annotation on a subclass and says so'
        Exception e = thrown()
        e.message.contains('cannot be applied to a subclass')
        e.message.contains('root entity')
    }
}

@Entity
class RestrictionRootEntity {

    String name
}

@Entity
@SQLRestriction('active = true')
class RestrictedChildEntity extends RestrictionRootEntity {

    Boolean active
}
