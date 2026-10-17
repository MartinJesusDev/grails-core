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
package org.grails.datastore.gorm.query.criteria

import spock.lang.Specification

/**
 * Compile-time coverage for the static domain entry points declared on the {@code GormEntity}
 * trait ({@code where}/{@code whereLazy}/{@code whereAny}/{@code find}/{@code findAll}). The
 * {@code DetachedCriteria} instance API already declares
 * {@code strategy = Closure.DELEGATE_FIRST}; the static domain API did not, so the nested subquery
 * form from #16425 did not type-check when the receiver is the domain class itself.
 *
 * The sources are compiled, not executed: the datamapping core unit tests have no datastore
 * registered. Runtime coverage of the same forms lives in the datastore test suites and the TCK.
 */
class GormEntityWhereCompileStaticSpec extends Specification {

    void 'a statically compiled domain where compiles a nested criteria subquery'() {
        expect:
        compile(EXPLICIT_SUBQUERY_SOURCE) != null
    }

    void 'a statically compiled domain where keeps the where query syntax compiling'() {
        expect:
        compile(WHERE_QUERY_SOURCE) != null
    }

    void 'a statically compiled domain whereLazy compiles a criteria closure'() {
        expect:
        compile(WHERE_LAZY_SOURCE) != null
    }

    void 'a statically compiled domain whereAny compiles a criteria closure'() {
        expect:
        compile(WHERE_ANY_SOURCE) != null
    }

    void 'a statically compiled domain find and findAll compile criteria closures'() {
        expect:
        compile(FINDERS_SOURCE) != null
    }

    static Class compile(String source) {
        new GroovyClassLoader(GormEntityWhereCompileStaticSpec.classLoader).parseClass(source)
    }

    private static final String EXPLICIT_SUBQUERY_SOURCE = '''
import grails.gorm.DetachedCriteria
import grails.gorm.annotation.Entity
import groovy.transform.CompileStatic

@CompileStatic
class GormWhereExplicitHolder {
    static DetachedCriteria<GormWhereAuthor> run() {
        GormWhereAuthor.where {
            exists(GormWhereBook.where { eq('title', 'X') }.id())
        }
    }
}

@Entity
class GormWhereAuthor {
    String name
}

@Entity
class GormWhereBook {
    String title
}
'''

    private static final String WHERE_QUERY_SOURCE = '''
import grails.gorm.DetachedCriteria
import grails.gorm.annotation.Entity
import groovy.transform.CompileStatic

@CompileStatic
class GormWhereSyntaxHolder {
    static DetachedCriteria<GormWhereSyntaxAuthor> run() {
        GormWhereSyntaxAuthor.where { name == 'A' }
    }
}

@Entity
class GormWhereSyntaxAuthor {
    String name
}
'''

    private static final String WHERE_LAZY_SOURCE = '''
import grails.gorm.DetachedCriteria
import grails.gorm.annotation.Entity
import groovy.transform.CompileStatic

@CompileStatic
class GormWhereLazyHolder {
    static DetachedCriteria<GormWhereLazyAuthor> run() {
        GormWhereLazyAuthor.whereLazy { eq('name', 'A') }
    }
}

@Entity
class GormWhereLazyAuthor {
    String name
}
'''

    private static final String WHERE_ANY_SOURCE = '''
import grails.gorm.DetachedCriteria
import grails.gorm.annotation.Entity
import groovy.transform.CompileStatic

@CompileStatic
class GormWhereAnyHolder {
    static DetachedCriteria<GormWhereAnyAuthor> run() {
        GormWhereAnyAuthor.whereAny { eq('name', 'A') }
    }
}

@Entity
class GormWhereAnyAuthor {
    String name
}
'''

    private static final String FINDERS_SOURCE = '''
import grails.gorm.annotation.Entity
import groovy.transform.CompileStatic

@CompileStatic
class GormWhereFindersHolder {
    static List<GormWhereFinderAuthor> findAll() {
        GormWhereFinderAuthor.findAll { eq('name', 'A') }
    }

    static GormWhereFinderAuthor find() {
        GormWhereFinderAuthor.find { eq('name', 'A') }
    }
}

@Entity
class GormWhereFinderAuthor {
    String name
}
'''
}
