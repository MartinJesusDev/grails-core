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

import java.lang.reflect.Modifier

import jakarta.persistence.criteria.AbstractQuery
import jakarta.persistence.criteria.From
import jakarta.persistence.criteria.Predicate

import grails.gorm.DetachedCriteria
import grails.gorm.annotation.Entity
import grails.gorm.hibernate.HibernateEntity
import org.grails.datastore.mapping.query.Query
import org.grails.orm.hibernate.cfg.domainbinding.hibernate.GrailsHibernatePersistentEntity
import org.grails.orm.hibernate.query.EqualsIgnoreCase
import org.grails.orm.hibernate.query.HibernateAlias
import org.grails.orm.hibernate.query.JpaQueryContext
import org.grails.orm.hibernate.query.PredicateGenerator
import org.grails.orm.hibernate.query.SqlRestriction

/**
 * Guards the criterion registry of the Hibernate 7 translator.
 *
 * {@code PredicateGenerator} dispatches on the criterion class with a chain of instanceof checks
 * and returns a null predicate only for the metadata criteria it documents. A criterion that is
 * not handled throws instead of disappearing, because a silently dropped restriction returns more
 * rows than the caller asked for. Two properties keep that true as the API grows:
 *
 * - the set of concrete {@link Query.Criterion} classes declared on {@link Query} must match the
 *   set this spec feeds to the translator, so a new criterion cannot be added without a case here;
 * - every criterion must translate to a predicate (or, for a documented metadata criterion, to
 *   null), and an unknown criterion must be rejected with an exception.
 */
class CriteriaTranslatorCompletenessSpec extends HibernateGormDatastoreSpec {

    private PredicateGenerator generator
    private AbstractQuery<?> criteriaQuery
    private From<?, ?> root
    private JpaQueryContext context
    private GrailsHibernatePersistentEntity entity

    @Override
    void setupSpec() {
        manager.registerDomainClasses(TranslatorCompletenessEntity, TranslatorCompletenessChild)
    }

    private void initTranslator() {
        criteriaQuery = getCriteriaBuilder().createQuery(TranslatorCompletenessEntity)
        root = criteriaQuery.from(TranslatorCompletenessEntity)
        context = JpaQueryContext.forSubquery(null, [], root)
        entity = getPersistentEntity(TranslatorCompletenessEntity)
        generator = new PredicateGenerator(getCriteriaBuilder(), getDatastore().mappingContext.conversionService)
    }

    void 'every concrete criterion class declared on Query is covered by this spec'() {
        given:
        initTranslator()
        Set<String> declared = Query.declaredClasses.findAll { Class type ->
            !type.interface && !Modifier.isAbstract(type.modifiers) && Query.Criterion.isAssignableFrom(type)
        }.collect { Class type -> type.simpleName } as Set

        and: 'the concrete base classes that are not criteria of their own'
        Set<String> baseClasses = ['PropertyCriterion', 'SubqueryCriterion', 'PropertyComparisonCriterion', 'PropertyNameCriterion'] as Set

        and: 'the criteria declared outside Query'
        Set<String> external = [EqualsIgnoreCase.simpleName, SqlRestriction.simpleName, HibernateAlias.simpleName] as Set

        and: 'the criterion classes this spec feeds to the translator'
        Set<String> covered = criteria().values().collect { Query.QueryElement criterion -> criterion.class.simpleName } as Set

        expect: 'a new criterion fails this spec until it is added to the coverage map'
        declared - baseClasses + external == covered
    }

    void 'every criterion translates to a predicate or is a documented metadata criterion'() {
        given:
        initTranslator()
        Map<String, Predicate> translated = criteria().collectEntries { String name, Query.QueryElement criterion ->
            [(name): (Predicate) generator.handleCriterion(criteriaQuery, root, context, entity, criterion)]
        }

        expect: 'only the alias is metadata; everything else must produce a predicate'
        translated.findAll { String name, Predicate predicate -> predicate == null }.keySet() == ['HibernateAlias'] as Set
    }

    void 'an unknown criterion is rejected instead of being dropped'() {
        given:
        initTranslator()

        when:
        generator.handleCriterion(criteriaQuery, root, context, entity, [:] as Query.Criterion)

        then:
        thrown(IllegalArgumentException)
    }

    private Map<String, Query.QueryElement> criteria() {
        DetachedCriteria<TranslatorCompletenessEntity> subquery = TranslatorCompletenessEntity.where {
            projections { property('age') }
            eq('name', 'a')
        }
        return [
                Equals                   : new Query.Equals('name', 'a'),
                NotEquals                : new Query.NotEquals('name', 'a'),
                EqualsIgnoreCase         : new EqualsIgnoreCase('name', 'a'),
                Like                     : new Query.Like('name', 'a%'),
                ILike                    : new Query.ILike('name', 'a%'),
                RLike                    : new Query.RLike('name', 'a.*'),
                GreaterThan              : new Query.GreaterThan('age', 1),
                GreaterThanEquals        : new Query.GreaterThanEquals('age', 1),
                LessThan                 : new Query.LessThan('age', 1),
                LessThanEquals           : new Query.LessThanEquals('age', 1),
                Between                  : new Query.Between('age', 1, 2),
                InValues                 : new Query.In('name', ['a']),
                InSubquery               : new Query.In('age', subquery),
                NotIn                    : new Query.NotIn('age', subquery),
                SizeEquals               : new Query.SizeEquals('children', 1),
                SizeNotEquals            : new Query.SizeNotEquals('children', 1),
                SizeGreaterThan          : new Query.SizeGreaterThan('children', 1),
                SizeGreaterThanEquals    : new Query.SizeGreaterThanEquals('children', 1),
                SizeLessThan             : new Query.SizeLessThan('children', 1),
                SizeLessThanEquals       : new Query.SizeLessThanEquals('children', 1),
                IsNull                   : new Query.IsNull('name'),
                IsNotNull                : new Query.IsNotNull('name'),
                IsEmpty                  : new Query.IsEmpty('children'),
                IsNotEmpty               : new Query.IsNotEmpty('children'),
                IdEquals                 : new Query.IdEquals(1L),
                EqualsProperty           : new Query.EqualsProperty('name', 'alias'),
                NotEqualsProperty        : new Query.NotEqualsProperty('name', 'alias'),
                GreaterThanProperty      : new Query.GreaterThanProperty('age', 'otherAge'),
                GreaterThanEqualsProperty: new Query.GreaterThanEqualsProperty('age', 'otherAge'),
                LessThanProperty         : new Query.LessThanProperty('age', 'otherAge'),
                LessThanEqualsProperty   : new Query.LessThanEqualsProperty('age', 'otherAge'),
                Exists                   : new Query.Exists(subquery),
                NotExists                : new Query.NotExists(subquery),
                EqualsAll                : new Query.EqualsAll('age', subquery),
                NotEqualsAll             : new Query.NotEqualsAll('age', subquery),
                GreaterThanAll           : new Query.GreaterThanAll('age', subquery),
                GreaterThanSome          : new Query.GreaterThanSome('age', subquery),
                GreaterThanEqualsAll     : new Query.GreaterThanEqualsAll('age', subquery),
                GreaterThanEqualsSome    : new Query.GreaterThanEqualsSome('age', subquery),
                LessThanAll              : new Query.LessThanAll('age', subquery),
                LessThanSome             : new Query.LessThanSome('age', subquery),
                LessThanEqualsAll        : new Query.LessThanEqualsAll('age', subquery),
                LessThanEqualsSome       : new Query.LessThanEqualsSome('age', subquery),
                Conjunction              : new Query.Conjunction(),
                Disjunction              : new Query.Disjunction(),
                Negation                 : new Query.Negation(),
                SqlRestriction           : new SqlRestriction('length(name) > 0', []),
                HibernateAlias           : new HibernateAlias('name', 'n'),
        ] as LinkedHashMap<String, Query.QueryElement>
    }
}

@Entity
class TranslatorCompletenessEntity implements HibernateEntity<TranslatorCompletenessEntity> {

    String name
    String alias
    Integer age
    Integer otherAge

    static hasMany = [children: TranslatorCompletenessChild]
}

@Entity
class TranslatorCompletenessChild implements HibernateEntity<TranslatorCompletenessChild> {

    String name
}
