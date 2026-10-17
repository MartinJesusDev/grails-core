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

import java.lang.reflect.Method
import java.lang.reflect.Modifier

import groovy.lang.DelegatesTo

import grails.gorm.DetachedCriteria
import grails.gorm.api.GormStaticOperations
import org.grails.datastore.gorm.GormEntity
import org.grails.datastore.gorm.GormStaticApi
import org.grails.datastore.gorm.multitenancy.TenantDelegatingGormOperations

import spock.lang.Specification

/**
 * Guards the compile-time contract of the criteria closure API.
 *
 * Every public method that accepts a criteria definition as a {@link Closure} must declare
 * {@code strategy = Closure.DELEGATE_FIRST} in its {@link DelegatesTo}. Without it the static
 * compiler assumes OWNER_FIRST and a nested criteria closure resolves its calls through the
 * enclosing closure's delegate (GROOVY-9283, #16425). A method added without the annotation
 * silently reintroduces the bug, so this spec fails on the missing declaration instead.
 *
 * The static domain entry points are checked through {@code GormEntity$Trait$Helper}, where Groovy
 * places the static methods of the GormEntity trait, and through the classes that declare or
 * forward them.
 */
class CriteriaClosureApiGuardSpec extends Specification {

    private static final Class GORM_ENTITY_TRAIT_HELPER = Class.forName(GormEntity.name + '$Trait$Helper')
    private static final Set<String> DOMAIN_ENTRY_POINTS = ['where', 'whereLazy', 'whereAny', 'find', 'findAll'] as Set

    void 'every public criteria closure method declares DELEGATE_FIRST'() {
        expect:
        offenders.isEmpty()
    }

    void 'the guard covers the criteria API it is meant to protect'() {
        expect: 'a missing annotation would be caught, so the checked set is far from empty'
        checkedMethods.size() > 30
        checkedMethods.contains('DetachedCriteria.where(Closure)')
        checkedMethods.contains('AbstractDetachedCriteria.projections(Closure)')
        checkedMethods.contains('Helper.where(Class, Closure)')
        checkedMethods.contains('GormStaticOperations.where(Closure)')
        checkedMethods.contains('GormStaticApi.where(Closure)')
        checkedMethods.contains('TenantDelegatingGormOperations.where(Closure)')
    }

    private static List<String> getOffenders() {
        criteriaClosureMethods().findAll { Method method ->
            DelegatesTo delegatesTo = delegateToOf(method)
            delegatesTo == null || delegatesTo.strategy() != Closure.DELEGATE_FIRST
        }.collect { Method method -> signature(method) }
    }

    private static List<String> getCheckedMethods() {
        criteriaClosureMethods().collect { Method method -> signature(method) }
    }

    private static List<Method> criteriaClosureMethods() {
        List<Class> instanceApi = [AbstractDetachedCriteria, DetachedCriteria]
        List<Class> domainApi = [
                GORM_ENTITY_TRAIT_HELPER,
                GormStaticOperations,
                GormStaticApi,
                TenantDelegatingGormOperations,
        ]
        return instanceApi.collectMany { Class type -> closureMethods(type, null) } +
                domainApi.collectMany { Class type -> closureMethods(type, DOMAIN_ENTRY_POINTS) }
    }

    /**
     * Public criteria closure methods of the type. Bridge and synthetic methods are skipped: a
     * covariant override produces a bridge without parameter annotations next to the real method.
     */
    private static List<Method> closureMethods(Class type, Set<String> methodNames) {
        type.declaredMethods.findAll { Method method ->
            Modifier.isPublic(method.modifiers) && !method.synthetic && !method.bridge &&
                    method.parameterTypes.contains(Closure) &&
                    (methodNames == null || methodNames.contains(method.name))
        }
    }

    private static DelegatesTo delegateToOf(Method method) {
        int closureIndex = method.parameterTypes.findIndexOf { Class parameterType -> parameterType == Closure }
        return method.parameterAnnotations[closureIndex].find { it instanceof DelegatesTo } as DelegatesTo
    }

    private static String signature(Method method) {
        "${method.declaringClass.simpleName}.${method.name}(${method.parameterTypes*.simpleName.join(', ')})"
    }
}
