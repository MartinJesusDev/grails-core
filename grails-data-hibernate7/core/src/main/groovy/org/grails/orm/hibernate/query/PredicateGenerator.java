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
package org.grails.orm.hibernate.query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.ParameterExpression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;

import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaSubQuery;

import org.springframework.core.convert.ConversionService;

import grails.gorm.DetachedCriteria;
import org.grails.datastore.gorm.query.criteria.DetachedAssociationCriteria;
import org.grails.datastore.mapping.core.exceptions.ConfigurationException;
import org.grails.datastore.mapping.model.PersistentEntity;
import org.grails.datastore.mapping.model.PersistentProperty;
import org.grails.datastore.mapping.model.types.Association;
import org.grails.datastore.mapping.query.Projections;
import org.grails.datastore.mapping.query.Query;
import org.grails.datastore.mapping.query.api.QueryableCriteria;
import org.grails.datastore.mapping.query.criteria.FunctionCallingCriterion;
import org.grails.orm.hibernate.cfg.domainbinding.hibernate.GrailsHibernatePersistentEntity;
import org.grails.orm.hibernate.cfg.domainbinding.hibernate.HibernatePersistentProperty;
import org.grails.orm.hibernate.cfg.domainbinding.hibernate.HibernateToManyProperty;

/**
 * A class that generates predicates for a given list of criteria.
 *
 * @since 8.0
 */
public class PredicateGenerator {

    /**
     * Extension point for user-defined criterion handlers. Register a handler for a custom
     * {@link Query.Criterion} subclass to have it converted to a JPA {@link Predicate} during
     * query execution. Registered handlers are checked first, before any built-in criterion
     * handling, so a handler can also override built-in behavior.
     *
     * <p>Example registration (e.g., in {@code BootStrap.groovy}):
     * <pre>
     *     PredicateGenerator.registerCriterionHandler(MyCustomCriterion) { query, root, cb, criterion -&gt;
     *         def c = criterion as MyCustomCriterion
     *         cb.like(cb.cast(root.get(c.property), String), c.value)
     *     }
     * </pre>
     *
     * @param <C> the specific criterion type
     */
    @FunctionalInterface
    public interface CriterionHandler {

        Predicate handle(
            AbstractQuery<?> criteriaQuery,
            From<?, ?> root,
            HibernateCriteriaBuilder criteriaBuilder,
            Query.Criterion criterion
        );
    }

    /**
     * SPI for contributing a {@link CriterionHandler} via {@link ServiceLoader}.
     *
     * <p>Create an implementation and register it in
     * {@code META-INF/services/org.grails.orm.hibernate.query.PredicateGenerator$CriterionHandlerProvider}
     * so that it is discovered automatically on first query execution — no BootStrap registration needed.
     *
     * <pre>
     *     // Example implementation (Groovy)
     *     class MyHandlerProvider implements PredicateGenerator.CriterionHandlerProvider {
     *         Class&lt;? extends Query.Criterion&gt; criterionType() { MyCriterion }
     *         PredicateGenerator.CriterionHandler criterionHandler() {
     *             { query, root, cb, criterion -&gt; ... } as PredicateGenerator.CriterionHandler
     *         }
     *     }
     * </pre>
     */
    public interface CriterionHandlerProvider {

        Class<? extends Query.Criterion> criterionType();

        CriterionHandler criterionHandler();
    }

    public static final Map<Class<? extends Query.Criterion>, CriterionHandler> CUSTOM_HANDLERS =
        new ConcurrentHashMap<>();

    private static final AtomicBoolean SERVICE_LOADERS_INITIALIZED = new AtomicBoolean(false);

    private static void loadServiceProviders() {
        if (SERVICE_LOADERS_INITIALIZED.compareAndSet(false, true)) {
            ServiceLoader.load(CriterionHandlerProvider.class,
                    Thread.currentThread().getContextClassLoader())
                .forEach(p -> CUSTOM_HANDLERS.putIfAbsent(p.criterionType(), p.criterionHandler()));
        }
    }

    /**
     * Registers a {@link CriterionHandler} for the given criterion type. The handler is called
     * before any built-in criterion logic when a criterion of exactly that type is encountered.
     * Programmatic registration takes precedence over {@link ServiceLoader}-discovered handlers.
     *
     * @param type    the exact criterion class to handle
     * @param handler the handler that converts the criterion to a JPA {@link Predicate}
     */
    public static void registerCriterionHandler(Class<? extends Query.Criterion> type, CriterionHandler handler) {
        CUSTOM_HANDLERS.put(type, handler);
    }

    /**
     * Removes all registered custom criterion handlers and resets the ServiceLoader flag.
     * Useful for test cleanup.
     */
    public static void clearCustomCriterionHandlers() {
        CUSTOM_HANDLERS.clear();
        SERVICE_LOADERS_INITIALIZED.set(false);
    }

    private final HibernateCriteriaBuilder criteriaBuilder;
    private final ConversionService conversionService;

    public PredicateGenerator(HibernateCriteriaBuilder criteriaBuilder, ConversionService conversionService) {
        this.criteriaBuilder = criteriaBuilder;
        this.conversionService = conversionService;
    }

    public Predicate[] getPredicates(
        AbstractQuery<?> criteriaQuery,
        From<?, ?> root,
        List<? extends Query.QueryElement> criteria,
        JpaQueryContext fromsByProvider,
        GrailsHibernatePersistentEntity entity) {
        return criteria.stream()
            .map(c -> handleCriterion(criteriaQuery, root, fromsByProvider, entity, c))
            .filter(Objects::nonNull)
            .toList()
            .toArray(new Predicate[0]);
    }

    private boolean isCollectionPath(Expression<?> expression) {
        if (expression instanceof jakarta.persistence.criteria.Path<?> path) {
            return Collection.class.isAssignableFrom(path.getJavaType());
        }
        return false;
    }

    public Predicate handleCriterion(
        AbstractQuery<?> criteriaQuery,
        From<?, ?> root,
        JpaQueryContext fromsByProvider,
        GrailsHibernatePersistentEntity entity,
        Query.QueryElement criterion) {

        loadServiceProviders();

        if (criterion instanceof Query.Criterion c) {
            CriterionHandler customHandler = CUSTOM_HANDLERS.get(c.getClass());
            if (customHandler != null) {
                return customHandler.handle(criteriaQuery, root, criteriaBuilder, c);
            }
        }

        if (criterion instanceof SqlRestriction sqlRestriction) {
            return handleSqlRestriction(root, fromsByProvider, entity, sqlRestriction);
        } else if (criterion instanceof Query.Junction junction) {
            return handleJunction(criteriaQuery, root, fromsByProvider, entity, junction);
        } else if (criterion instanceof Query.DistinctProjection) {
            return criteriaBuilder.conjunction();
        } else if (criterion instanceof DetachedAssociationCriteria<?> c) {
            return handleAssociationCriteria(criteriaQuery, fromsByProvider, c);
        } else if (criterion instanceof HibernateAssociationQuery haq) {
            return handleHibernateAssociationQuery(criteriaQuery, fromsByProvider, haq);
        } else if (criterion instanceof Query.SubqueryCriterion c) {
            return handleSubqueryCriterion(criteriaQuery, root, fromsByProvider, entity, c);
        } else if (criterion instanceof Query.IdEquals idEquals) {
            String propertyName = entity.getIdentity().getName();
            Expression<?> propertyPath = fromsByProvider.getFullyQualifiedExpression(propertyName);
            return criteriaBuilder.equal(propertyPath, convertValue(entity, propertyName, idEquals.getValue(), propertyPath));
        } else if (criterion instanceof Query.PropertyCriterion pc) {
            return handlePropertyCriterion(criteriaQuery, root, fromsByProvider, entity, pc);
        } else if (criterion instanceof Query.PropertyComparisonCriterion c) {
            return handlePropertyComparisonCriterion(fromsByProvider, c);
        } else if (criterion instanceof FunctionCallingCriterion c) {
            return handleFunctionCallingCriterion(fromsByProvider, c);
        } else if (criterion instanceof Query.PropertyNameCriterion c) {
            return handlePropertyNameCriterion(fromsByProvider, c);
        } else if (criterion instanceof Query.Exists c) {
            return handleExists(
                criteriaQuery, fromsByProvider, c);
        } else if (criterion instanceof Query.NotExists c) {
            return criteriaBuilder.not(handleExists(
                criteriaQuery, fromsByProvider, new Query.Exists(c.getSubquery())));
        } else if (criterion instanceof HibernateAlias) {
            return null; // Metadata only, handled by JpaQueryContext
        }
        throw new IllegalArgumentException("Unsupported criterion: " + criterion);
    }

    @SuppressWarnings("unchecked")
    private Predicate handleSubqueryCriterion(AbstractQuery<?> criteriaQuery, From<?, ?> root, JpaQueryContext fromsByProvider, GrailsHibernatePersistentEntity entity, Query.SubqueryCriterion c) {
        Expression<?> propertyPath = fromsByProvider.getFullyQualifiedExpression(c.getProperty());
        QueryableCriteria<?> qc = c.getValue();
        materializeLazyCriteria(qc);

        // If it's a comparison criterion, we expect the subquery to return the same type as the property
        Class<?> expectedType = propertyPath != null ? propertyPath.getJavaType() : qc.getPersistentEntity().getJavaClass();

        Query.ProjectionList projectionList = new Query.ProjectionList();
        for (Query.Projection p : qc.getProjections()) {
            projectionList.add(p);
        }

        // A subquery that projects nothing defaults to its own property with the compared name when
        // it has one. The default is local on purpose: translating a query must not mutate the criteria.
        if (projectionList.isEmpty()) {
            PersistentProperty prop = qc.getPersistentEntity().getPropertyByName(c.getProperty());
            if (prop != null) {
                projectionList.add(Projections.property(c.getProperty()));
            }
        }

        Subquery<?> subquery = criteriaQuery.subquery(expectedType);
        var creator = new JpaCriteriaQueryCreator(projectionList, criteriaBuilder, (GrailsHibernatePersistentEntity) qc.getPersistentEntity(), (DetachedCriteria) qc, conversionService);
        creator.setParentContext(fromsByProvider);

        creator.populateSubquery((JpaSubQuery) subquery);

        if (c instanceof Query.EqualsAll) {
            return criteriaBuilder.equal(propertyPath, (Expression) criteriaBuilder.all(subquery));
        } else if (c instanceof Query.NotEqualsAll) {
            return criteriaBuilder.notEqual(propertyPath, (Expression) criteriaBuilder.all(subquery));
        } else if (c instanceof Query.GreaterThanAll) {
            return criteriaBuilder.greaterThan((Expression<? extends Comparable>) propertyPath, (Expression) criteriaBuilder.all(subquery));
        } else if (c instanceof Query.GreaterThanSome) {
            return criteriaBuilder.greaterThan((Expression<? extends Comparable>) propertyPath, (Expression) criteriaBuilder.some(subquery));
        } else if (c instanceof Query.GreaterThanEqualsAll) {
            return criteriaBuilder.greaterThanOrEqualTo((Expression<? extends Comparable>) propertyPath, (Expression) criteriaBuilder.all(subquery));
        } else if (c instanceof Query.GreaterThanEqualsSome) {
            return criteriaBuilder.greaterThanOrEqualTo((Expression<? extends Comparable>) propertyPath, (Expression) criteriaBuilder.some(subquery));
        } else if (c instanceof Query.LessThanAll) {
            return criteriaBuilder.lessThan((Expression<? extends Comparable>) propertyPath, (Expression) criteriaBuilder.all(subquery));
        } else if (c instanceof Query.LessThanSome) {
            return criteriaBuilder.lessThan((Expression<? extends Comparable>) propertyPath, (Expression) criteriaBuilder.some(subquery));
        } else if (c instanceof Query.LessThanEqualsAll) {
            return criteriaBuilder.lessThanOrEqualTo((Expression<? extends Comparable>) propertyPath, (Expression) criteriaBuilder.all(subquery));
        } else if (c instanceof Query.LessThanEqualsSome) {
            return criteriaBuilder.lessThanOrEqualTo((Expression<? extends Comparable>) propertyPath, (Expression) criteriaBuilder.some(subquery));
        } else if (c instanceof Query.NotIn) {
            return criteriaBuilder.not(propertyPath.in(subquery));
        }

        throw new UnsupportedOperationException("Unsupported subquery criterion: " + c.getClass().getName());
    }

    private Predicate handleJunction(
        AbstractQuery<?> criteriaQuery,
        From<?, ?> root_,
        JpaQueryContext fromsByProvider,
        GrailsHibernatePersistentEntity entity,
        Query.Junction junction) {
        List<Query.Criterion> criteriaList = junction.getCriteria();
        Predicate[] predicates = getPredicates(criteriaQuery, root_, criteriaList, fromsByProvider, entity);
        if (junction instanceof Query.Conjunction) {
            return criteriaBuilder.and(predicates);
        } else if (junction instanceof Query.Disjunction) {
            return criteriaBuilder.or(predicates);
        } else if (junction instanceof Query.Negation) {
            return criteriaBuilder.not(criteriaBuilder.or(predicates));
        }
        throw new IllegalArgumentException("Unsupported junction: " + junction);
    }

    private Predicate handleAssociationCriteria(
        AbstractQuery<?> criteriaQuery,
        JpaQueryContext fromsByProvider,
        DetachedAssociationCriteria<?> associationCriteria) {
        String associationName = associationCriteria.getAssociationPath();
        From<?, ?> associationRoot = fromsByProvider.getFrom(associationName);
        if (associationRoot == null) {
            // Check if we already have it in our parent or alias map
            Expression<?> expr = fromsByProvider.getFullyQualifiedExpression(associationName);
            if (expr instanceof From<?, ?> from) {
                associationRoot = from;
            } else {
                associationRoot = fromsByProvider.getRoot().join(associationName);
                fromsByProvider.addFrom(associationName, associationRoot);
            }
        }

        // Create a nested context for this association
        JpaQueryContext nestedContext = new JpaQueryContext(fromsByProvider, null, associationRoot);

        GrailsHibernatePersistentEntity associatedEntity = (GrailsHibernatePersistentEntity) associationCriteria.getAssociation().getAssociatedEntity();
        List<Query.Criterion> criteriaList = associationCriteria.getCriteria();
        return criteriaBuilder.and(getPredicates(criteriaQuery, associationRoot, criteriaList, nestedContext, associatedEntity));
    }

    private Predicate handleHibernateAssociationQuery(
        AbstractQuery<?> criteriaQuery,
        JpaQueryContext fromsByProvider,
        HibernateAssociationQuery associationQuery) {
        String associationName = associationQuery.associationPath;
        From<?, ?> associationRoot = fromsByProvider.getFrom(associationName);
        if (associationRoot == null) {
            Expression<?> expr = fromsByProvider.getFullyQualifiedExpression(associationName);
            if (expr instanceof From<?, ?> from) {
                associationRoot = from;
            } else {
                associationRoot = fromsByProvider.getRoot().join(associationName, JoinType.INNER);
                fromsByProvider.addFrom(associationName, associationRoot);
            }
        }

        // Create a nested context for this association
        JpaQueryContext nestedContext = new JpaQueryContext(fromsByProvider, null, associationRoot);

        GrailsHibernatePersistentEntity associatedEntity = associationQuery.getEntity();
        List<Query.Criterion> criteriaList = associationQuery.getAssociationCriteria();
        return criteriaBuilder.and(getPredicates(criteriaQuery, associationRoot, criteriaList, nestedContext, associatedEntity));
    }

    @SuppressWarnings("unchecked")
    private Predicate handlePropertyCriterion(
        AbstractQuery<?> criteriaQuery,
        From<?, ?> root,
        JpaQueryContext fromsByProvider,
        GrailsHibernatePersistentEntity entity,
        Query.PropertyCriterion pc) {
        String propertyName = pc.getProperty();
        Expression<?> propertyPath = fromsByProvider.getFullyQualifiedExpression(propertyName);
        if (propertyPath == null) {
            throw new ConfigurationException("Cannot use comparison criteria on non-existent property [" + propertyName + "] of class [" + entity.getJavaClass().getName() + "]");
        }

        if (pc instanceof EqualsIgnoreCase) {
            return handleEqualsIgnoreCase(criteriaQuery, pc, propertyPath, fromsByProvider, entity);
        } else if (pc instanceof Query.Equals) {
            return handleEquals(criteriaQuery, pc, propertyPath, fromsByProvider, entity);
        } else if (pc instanceof Query.NotEquals) {
            return handleNotEquals(criteriaQuery, pc, propertyPath, fromsByProvider, entity);
        } else if (pc instanceof Query.ILike) {
            return criteriaBuilder.ilike((Expression<String>) propertyPath, (String) convertValue(entity, propertyName, pc.getValue(), propertyPath));
        } else if (pc instanceof Query.RLike rLike) {
            return handleRLike((Expression<String>) propertyPath, rLike);
        } else if (pc instanceof Query.Like) {
            return criteriaBuilder.like((Expression<String>) propertyPath, (String) convertValue(entity, propertyName, pc.getValue(), propertyPath));
        } else if (pc instanceof Query.GreaterThan) {
            if (pc.getValue() instanceof QueryableCriteria qc) {
                return handleComparisonWithSubquery(criteriaQuery, pc, propertyPath, fromsByProvider, Comparison.GREATER);
            }
            Object value = convertComparisonValue(entity, propertyName, pc.getValue(), fromsByProvider, propertyPath);
            return value instanceof Comparable<?> comparable ?
                criteriaBuilder.greaterThan((Expression<? extends Comparable>) propertyPath, (Comparable) comparable) :
                criteriaBuilder.greaterThan((Expression<? extends Comparable>) propertyPath, (Expression) asExpression(value, propertyPath, fromsByProvider));
        } else if (pc instanceof Query.GreaterThanEquals) {
            if (pc.getValue() instanceof QueryableCriteria qc) {
                return handleComparisonWithSubquery(criteriaQuery, pc, propertyPath, fromsByProvider, Comparison.GREATER_EQUALS);
            }
            Object value = convertComparisonValue(entity, propertyName, pc.getValue(), fromsByProvider, propertyPath);
            return value instanceof Comparable<?> comparable ?
                criteriaBuilder.greaterThanOrEqualTo((Expression<? extends Comparable>) propertyPath, (Comparable) comparable) :
                criteriaBuilder.greaterThanOrEqualTo((Expression<? extends Comparable>) propertyPath, (Expression) asExpression(value, propertyPath, fromsByProvider));
        } else if (pc instanceof Query.LessThan) {
            if (pc.getValue() instanceof QueryableCriteria qc) {
                return handleComparisonWithSubquery(criteriaQuery, pc, propertyPath, fromsByProvider, Comparison.LESS);
            }
            Object value = convertComparisonValue(entity, propertyName, pc.getValue(), fromsByProvider, propertyPath);
            return value instanceof Comparable<?> comparable ?
                criteriaBuilder.lessThan((Expression<? extends Comparable>) propertyPath, (Comparable) comparable) :
                criteriaBuilder.lessThan((Expression<? extends Comparable>) propertyPath, (Expression) asExpression(value, propertyPath, fromsByProvider));
        } else if (pc instanceof Query.LessThanEquals) {
            if (pc.getValue() instanceof QueryableCriteria qc) {
                return handleComparisonWithSubquery(criteriaQuery, pc, propertyPath, fromsByProvider, Comparison.LESS_EQUALS);
            }
            Object value = convertComparisonValue(entity, propertyName, pc.getValue(), fromsByProvider, propertyPath);
            return value instanceof Comparable<?> comparable ?
                criteriaBuilder.lessThanOrEqualTo((Expression<? extends Comparable>) propertyPath, (Comparable) comparable) :
                criteriaBuilder.lessThanOrEqualTo((Expression<? extends Comparable>) propertyPath, (Expression) asExpression(value, propertyPath, fromsByProvider));
        } else if (pc instanceof Query.In) {
            Object value = pc.getValue();
            if (value instanceof QueryableCriteria qc) {
                materializeLazyCriteria(qc);
                Class<?> expectedType = propertyPath != null ? propertyPath.getJavaType() : qc.getPersistentEntity().getJavaClass();

                Query.ProjectionList projectionList = new Query.ProjectionList();
                for (Object p : qc.getProjections()) {
                    projectionList.add((Query.Projection) p);
                }

                // The default projection is local on purpose: translating a query must not mutate the criteria.
                if (projectionList.isEmpty() && propertyPath != null) {
                    PersistentProperty prop = qc.getPersistentEntity().getPropertyByName(propertyName);
                    if (prop != null) {
                        projectionList.add(Projections.property(propertyName));
                    }
                }

                Subquery<?> subquery = criteriaQuery.subquery(expectedType);
                var creator = new JpaCriteriaQueryCreator(projectionList, criteriaBuilder, (GrailsHibernatePersistentEntity) qc.getPersistentEntity(), (DetachedCriteria) qc, conversionService);
                creator.setParentContext(fromsByProvider);

                creator.populateSubquery((JpaSubQuery) subquery);
                return propertyPath.in(subquery);
            }

            Collection<?> collection = value instanceof Collection ? (Collection<?>) value : Collections.singletonList(value);
            // A null member of the list matches no row in SQL, so it takes no part in the comparison
            // instead of failing the whole query.
            List<Object> converted = collection.stream()
                .filter(Objects::nonNull)
                .map(v -> convertValue(entity, propertyName, v, propertyPath))
                .collect(Collectors.toList());

            if (isCollectionPath(propertyPath)) {
                // For collection properties, we use "member of" for each value joined with OR
                if (converted.isEmpty()) {
                    return criteriaBuilder.disjunction(); // Always false for empty IN on collection
                }
                Predicate[] memberOfPredicates = converted.stream()
                    .map(v -> criteriaBuilder.isMember((Object) v, (Expression) propertyPath))
                    .toArray(Predicate[]::new);
                return criteriaBuilder.or(memberOfPredicates);
            }

            return propertyPath.in(converted);
        } else if (pc instanceof Query.Between between) {
            return criteriaBuilder.between((Expression<? extends Comparable>) propertyPath, (Comparable) convertValue(entity, propertyName, between.getFrom(), propertyPath), (Comparable) convertValue(entity, propertyName, between.getTo(), propertyPath));
        } else if (pc instanceof Query.SizeEquals) {
            return criteriaBuilder.equal(criteriaBuilder.size((Expression<java.util.Collection<?>>) propertyPath), (Integer) pc.getValue());
        } else if (pc instanceof Query.SizeNotEquals) {
            return criteriaBuilder.notEqual(criteriaBuilder.size((Expression<java.util.Collection<?>>) propertyPath), (Integer) pc.getValue());
        } else if (pc instanceof Query.SizeGreaterThan) {
            return criteriaBuilder.greaterThan(criteriaBuilder.size((Expression<java.util.Collection<?>>) propertyPath), (Integer) pc.getValue());
        } else if (pc instanceof Query.SizeGreaterThanEquals) {
            return criteriaBuilder.greaterThanOrEqualTo(criteriaBuilder.size((Expression<java.util.Collection<?>>) propertyPath), (Integer) pc.getValue());
        } else if (pc instanceof Query.SizeLessThan) {
            return criteriaBuilder.lessThan(criteriaBuilder.size((Expression<java.util.Collection<?>>) propertyPath), (Integer) pc.getValue());
        } else if (pc instanceof Query.SizeLessThanEquals) {
            return criteriaBuilder.lessThanOrEqualTo(criteriaBuilder.size((Expression<java.util.Collection<?>>) propertyPath), (Integer) pc.getValue());
        }

        throw new UnsupportedOperationException("Unsupported criterion: " + pc.getClass().getName());
    }

    /**
     * Returns a column of the entity's own table, whose table alias replaces {@code {alias}} in native SQL:
     * its identifier column, or the first one of a composite identifier. An identifier that is an association
     * stands for its foreign key columns, which are resolved the same way from the associated entity.
     */
    private static Expression<?> aliasColumn(From<?, ?> root, GrailsHibernatePersistentEntity entity) {
        Path<?> path = root;
        PersistentEntity current = entity;
        while (true) {
            PersistentProperty<?> identity = firstIdentifierProperty(current);
            if (identity == null) {
                throw new ConfigurationException("Cannot use {alias} in native SQL on class [" + entity.getJavaClass().getName() + "] without an identifier");
            }
            path = path.get(identity.getName());
            if (!(identity instanceof Association<?> association) || association.getAssociatedEntity() == null) {
                return path;
            }
            current = association.getAssociatedEntity();
        }
    }

    private static PersistentProperty<?> firstIdentifierProperty(PersistentEntity entity) {
        if (entity.getIdentity() != null) {
            return entity.getIdentity();
        }
        PersistentProperty<?>[] compositeIdentity = entity.getCompositeIdentity();
        return compositeIdentity == null || compositeIdentity.length == 0 ? null : compositeIdentity[0];
    }

    /**
     * Returns the first arguments of a function that renders native SQL: the SQL as a literal and, if it contains
     * {@code {alias}}, a column of the entity's own table whose table alias replaces it.
     */
    static List<Expression<?>> nativeSqlArguments(
            CriteriaBuilder criteriaBuilder, String sql, From<?, ?> root, GrailsHibernatePersistentEntity entity) {
        List<Expression<?>> arguments = new ArrayList<>();
        arguments.add(criteriaBuilder.literal(sql));
        if (sql.contains(GrailsSqlRestrictionFunction.ALIAS_PLACEHOLDER)) {
            arguments.add(aliasColumn(root, entity));
        }
        return arguments;
    }

    private Predicate handleSqlRestriction(From<?, ?> root, JpaQueryContext context, GrailsHibernatePersistentEntity entity, SqlRestriction restriction) {
        List<Expression<?>> arguments = nativeSqlArguments(criteriaBuilder, restriction.sql(), root, entity);
        for (Object value : restriction.values()) {
            ParameterExpression<?> parameter = criteriaBuilder.parameter(value.getClass());
            context.bindParameter(parameter, value);
            arguments.add(parameter);
        }
        return criteriaBuilder.isTrue(criteriaBuilder.function(
                GrailsSqlRestrictionFunction.NAME, Boolean.class, arguments.toArray(new Expression<?>[0])));
    }

    private Predicate handleRLike(Expression<String> propertyPath, Query.RLike c) {
        String pattern = c.getValue().toString().replaceAll("^/|/$", "");
        return criteriaBuilder.equal(
            criteriaBuilder.function(
                GrailsRLikeFunctionContributor.RLIKE,
                Boolean.class,
                propertyPath,
                criteriaBuilder.literal(pattern)),
            true);
    }

    @SuppressWarnings("unchecked")
    private Predicate handlePropertyComparisonCriterion(JpaQueryContext fromsByProvider, Query.PropertyComparisonCriterion c) {
        Expression<?> propertyPath = fromsByProvider.getFullyQualifiedExpression(c.getProperty());
        Expression<?> otherPropertyPath = fromsByProvider.getFullyQualifiedExpression(c.getOtherProperty());

        if (propertyPath == null) {
            throw new ConfigurationException("Cannot resolve property [" + c.getProperty() + "] in a property comparison criterion");
        }
        if (otherPropertyPath == null) {
            throw new ConfigurationException("Cannot resolve property [" + c.getOtherProperty() + "] in a property comparison criterion");
        }

        if (c instanceof Query.EqualsProperty) {
            return criteriaBuilder.equal(propertyPath, otherPropertyPath);
        } else if (c instanceof Query.NotEqualsProperty) {
            return criteriaBuilder.notEqual(propertyPath, otherPropertyPath);
        } else if (c instanceof Query.GreaterThanProperty) {
            return criteriaBuilder.greaterThan((Expression<? extends Comparable>) propertyPath, (Expression<? extends Comparable>) otherPropertyPath);
        } else if (c instanceof Query.GreaterThanEqualsProperty) {
            return criteriaBuilder.greaterThanOrEqualTo((Expression<? extends Comparable>) propertyPath, (Expression<? extends Comparable>) otherPropertyPath);
        } else if (c instanceof Query.LessThanProperty) {
            return criteriaBuilder.lessThan((Expression<? extends Comparable>) propertyPath, (Expression<? extends Comparable>) otherPropertyPath);
        } else if (c instanceof Query.LessThanEqualsProperty) {
            return criteriaBuilder.lessThanOrEqualTo((Expression<? extends Comparable>) propertyPath, (Expression<? extends Comparable>) otherPropertyPath);
        }

        throw new UnsupportedOperationException("Unsupported property comparison criterion: " + c.getClass().getName());
    }

    /**
     * Compares a property with a scalar subquery, for example {@code age > (select avg(age) ...)},
     * the form the manual documents for gt with a projection subquery.
     */
    @SuppressWarnings("unchecked")
    private Predicate handleComparisonWithSubquery(AbstractQuery<?> criteriaQuery, Query.PropertyCriterion pc, Expression<?> propertyPath, JpaQueryContext fromsByProvider, Comparison comparison) {
        QueryableCriteria<?> qc = (QueryableCriteria<?>) pc.getValue();
        materializeLazyCriteria(qc);
        Class<?> expectedType = propertyPath != null ? propertyPath.getJavaType() : qc.getPersistentEntity().getJavaClass();
        Subquery<?> subquery = criteriaQuery.subquery(expectedType);

        Query.ProjectionList projectionList = new Query.ProjectionList();
        for (Object p : qc.getProjections()) {
            projectionList.add((Query.Projection) p);
        }
        if (projectionList.isEmpty() && propertyPath != null) {
            String propertyName = pc.getProperty();
            if (propertyName.contains(grails.orm.HibernateCriteriaBuilder.ALIAS_SEPARATOR)) {
                propertyName = propertyName.split(grails.orm.HibernateCriteriaBuilder.ALIAS_SEPARATOR)[1];
            }
            PersistentProperty prop = qc.getPersistentEntity().getPropertyByName(propertyName);
            if (prop != null) {
                projectionList.add(Projections.property(propertyName));
            }
        }

        var creator = new JpaCriteriaQueryCreator(projectionList, criteriaBuilder, (GrailsHibernatePersistentEntity) qc.getPersistentEntity(), (DetachedCriteria) qc, conversionService);
        creator.setParentContext(fromsByProvider);
        creator.populateSubquery((JpaSubQuery) subquery);

        return switch (comparison) {
            case GREATER -> criteriaBuilder.greaterThan((Expression<? extends Comparable>) propertyPath, (Expression) subquery);
            case GREATER_EQUALS -> criteriaBuilder.greaterThanOrEqualTo((Expression<? extends Comparable>) propertyPath, (Expression) subquery);
            case LESS -> criteriaBuilder.lessThan((Expression<? extends Comparable>) propertyPath, (Expression) subquery);
            case LESS_EQUALS -> criteriaBuilder.lessThanOrEqualTo((Expression<? extends Comparable>) propertyPath, (Expression) subquery);
        };
    }

    private enum Comparison {
        GREATER, GREATER_EQUALS, LESS, LESS_EQUALS
    }

    /**
     * Translates a function call around a property, the where-query form {@code upper(name) == 'A'}:
     * the function wraps the property expression and the wrapped criterion supplies the operator and
     * the value. The functions the transformer supports are lower, upper, trim, length and the date
     * parts second, hour, minute, day, month and year.
     */
    @SuppressWarnings("unchecked")
    private Predicate handleFunctionCallingCriterion(JpaQueryContext fromsByProvider, FunctionCallingCriterion criterion) {
        String functionName = criterion.getFunctionName();
        if (criterion.isOnValue()) {
            throw new UnsupportedOperationException("A function on the value of a comparison is not supported: " + functionName);
        }
        Expression<?> propertyPath = fromsByProvider.getFullyQualifiedExpression(criterion.getProperty());
        if (propertyPath == null) {
            throw new ConfigurationException("Cannot resolve property [" + criterion.getProperty() + "] in a " + functionName + " function call");
        }
        Expression<?> left = applyFunction(functionName, propertyPath);
        Query.PropertyCriterion wrapped = criterion.getPropertyCriterion();
        Object value = convertFunctionValue(functionName, wrapped.getValue());

        if (wrapped instanceof Query.ILike) {
            return criteriaBuilder.ilike((Expression<String>) left, (String) value);
        } else if (wrapped instanceof Query.Like) {
            return criteriaBuilder.like((Expression<String>) left, (String) value);
        } else if (wrapped instanceof Query.Equals) {
            return criteriaBuilder.equal(left, value);
        } else if (wrapped instanceof Query.NotEquals) {
            return criteriaBuilder.notEqual(left, value);
        } else if (wrapped instanceof Query.GreaterThan) {
            return criteriaBuilder.greaterThan((Expression<? extends Comparable>) left, (Comparable) value);
        } else if (wrapped instanceof Query.GreaterThanEquals) {
            return criteriaBuilder.greaterThanOrEqualTo((Expression<? extends Comparable>) left, (Comparable) value);
        } else if (wrapped instanceof Query.LessThan) {
            return criteriaBuilder.lessThan((Expression<? extends Comparable>) left, (Comparable) value);
        } else if (wrapped instanceof Query.LessThanEquals) {
            return criteriaBuilder.lessThanOrEqualTo((Expression<? extends Comparable>) left, (Comparable) value);
        } else if (wrapped instanceof Query.In in) {
            Collection<Object> values = new ArrayList<>();
            for (Object v : in.getValues()) {
                values.add(convertFunctionValue(functionName, v));
            }
            return left.in(values);
        }
        throw new UnsupportedOperationException("Unsupported criterion in a function call: " + wrapped.getClass().getName());
    }

    private Expression<?> applyFunction(String functionName, Expression<?> expression) {
        return switch (functionName) {
            case "lower" -> criteriaBuilder.lower((Expression<String>) expression);
            case "upper" -> criteriaBuilder.upper((Expression<String>) expression);
            case "trim" -> criteriaBuilder.trim((Expression<String>) expression);
            case "length" -> criteriaBuilder.length((Expression<String>) expression);
            default -> criteriaBuilder.function(functionName, Integer.class, expression);
        };
    }

    private Object convertFunctionValue(String functionName, Object value) {
        if (value == null) {
            return null;
        }
        Class<?> targetType = switch (functionName) {
            case "lower", "upper", "trim" -> String.class;
            default -> Integer.class;
        };
        return conversionService.canConvert(value.getClass(), targetType) ? conversionService.convert(value, targetType) : value;
    }

    private Predicate handlePropertyNameCriterion(JpaQueryContext fromsByProvider, Query.PropertyNameCriterion c) {
        Expression<?> propertyPath = fromsByProvider.getFullyQualifiedExpression(c.getProperty());
        if (propertyPath == null) {
            throw new ConfigurationException("Cannot resolve property [" + c.getProperty() + "] in a " + c.getClass().getSimpleName() + " criterion");
        }
        if (((Object) c) instanceof Query.IsNull) {
            return criteriaBuilder.isNull(propertyPath);
        } else if (((Object) c) instanceof Query.IsNotNull) {
            return criteriaBuilder.isNotNull(propertyPath);
        } else if (((Object) c) instanceof Query.IsEmpty) {
            return criteriaBuilder.isEmpty((Expression<java.util.Collection<?>>) propertyPath);
        } else if (((Object) c) instanceof Query.IsNotEmpty) {
            return criteriaBuilder.isNotEmpty((Expression<java.util.Collection<?>>) propertyPath);
        }
        throw new UnsupportedOperationException("Unsupported property name criterion: " + c.getClass().getName());
    }

    @SuppressWarnings("unchecked")
    private Predicate handleEquals(AbstractQuery<?> criteriaQuery, Query.PropertyCriterion pc, Expression<?> propertyPath, JpaQueryContext fromsByProvider, GrailsHibernatePersistentEntity entity) {
        if (pc.getValue() instanceof QueryableCriteria qc) {
            materializeLazyCriteria(qc);
            Class<?> expectedType = propertyPath != null ? propertyPath.getJavaType() : qc.getPersistentEntity().getJavaClass();
            Subquery<?> subquery = criteriaQuery.subquery(expectedType);

            Query.ProjectionList projectionList = new Query.ProjectionList();
            for (Object p : qc.getProjections()) {
                projectionList.add((Query.Projection) p);
            }
            // The default projection is local on purpose: translating a query must not mutate the criteria.
            if (projectionList.isEmpty() && propertyPath != null) {
                String propertyName = pc.getProperty();
                if (propertyName.contains(grails.orm.HibernateCriteriaBuilder.ALIAS_SEPARATOR)) {
                    propertyName = propertyName.split(grails.orm.HibernateCriteriaBuilder.ALIAS_SEPARATOR)[1];
                }
                PersistentProperty prop = qc.getPersistentEntity().getPropertyByName(propertyName);
                if (prop != null) {
                    projectionList.add(Projections.property(propertyName));
                }
            }

            var creator = new JpaCriteriaQueryCreator(projectionList, criteriaBuilder, (GrailsHibernatePersistentEntity) qc.getPersistentEntity(), (DetachedCriteria) qc, conversionService);
            creator.setParentContext(fromsByProvider);
            creator.populateSubquery((JpaSubQuery) subquery);
            return criteriaBuilder.equal(propertyPath, subquery);
        } else {
            return criteriaBuilder.equal(propertyPath, convertComparisonValue(entity, pc.getProperty(), pc.getValue(), fromsByProvider, propertyPath));
        }
    }

    /**
     * Compares a {@link String} property with the value in lower case, as Hibernate 5's
     * {@code Restrictions.eq(...).ignoreCase()} did. Any other property is compared with plain equality.
     */
    @SuppressWarnings("unchecked")
    private Predicate handleEqualsIgnoreCase(AbstractQuery<?> criteriaQuery, Query.PropertyCriterion pc, Expression<?> propertyPath, JpaQueryContext fromsByProvider, GrailsHibernatePersistentEntity entity) {
        Object value = pc.getValue();
        if (!String.class.equals(propertyPath.getJavaType()) || !(value instanceof CharSequence)) {
            return handleEquals(criteriaQuery, pc, propertyPath, fromsByProvider, entity);
        }
        return criteriaBuilder.equal(
                criteriaBuilder.lower((Expression<String>) propertyPath),
                value.toString().toLowerCase(Locale.ROOT));
    }

    @SuppressWarnings("unchecked")
    private Predicate handleNotEquals(
        AbstractQuery<?> criteriaQuery,
        Query.PropertyCriterion pc,
        Expression<?> propertyPath,
        JpaQueryContext fromsByProvider,
        GrailsHibernatePersistentEntity entity) {
        Object value = pc.getValue();
        if (value == null) {
            return criteriaBuilder.isNotNull(propertyPath);
        }
        if (value instanceof QueryableCriteria qc) {
            materializeLazyCriteria(qc);
            Class<?> expectedType = propertyPath != null ? propertyPath.getJavaType() : qc.getPersistentEntity().getJavaClass();
            Subquery<?> subquery = criteriaQuery.subquery(expectedType);

            Query.ProjectionList projectionList = new Query.ProjectionList();
            for (Object p : qc.getProjections()) {
                projectionList.add((Query.Projection) p);
            }
            // The default projection is local on purpose: translating a query must not mutate the criteria.
            if (projectionList.isEmpty() && propertyPath != null) {
                String propertyName = pc.getProperty();
                if (propertyName.contains(grails.orm.HibernateCriteriaBuilder.ALIAS_SEPARATOR)) {
                    propertyName = propertyName.split(grails.orm.HibernateCriteriaBuilder.ALIAS_SEPARATOR)[1];
                }
                PersistentProperty prop = qc.getPersistentEntity().getPropertyByName(propertyName);
                if (prop != null) {
                    projectionList.add(Projections.property(propertyName));
                }
            }

            var creator = new JpaCriteriaQueryCreator(projectionList, criteriaBuilder, (GrailsHibernatePersistentEntity) qc.getPersistentEntity(), (DetachedCriteria) qc, conversionService);
            creator.setParentContext(fromsByProvider);
            creator.populateSubquery((JpaSubQuery) subquery);
            return criteriaBuilder.or(criteriaBuilder.notEqual(propertyPath, subquery), criteriaBuilder.isNull(propertyPath));
        }
        return criteriaBuilder.or(
            criteriaBuilder.notEqual(propertyPath, convertComparisonValue(entity, pc.getProperty(), value, fromsByProvider, propertyPath)),
            criteriaBuilder.isNull(propertyPath));
    }

    @SuppressWarnings("unchecked")
    private Predicate handleExists(
        AbstractQuery<?> criteriaQuery,
        JpaQueryContext fromsByProvider,
        Query.Exists exists) {
        QueryableCriteria subqueryCriteria = exists.getSubquery();
        materializeLazyCriteria(subqueryCriteria);
        GrailsHibernatePersistentEntity subqueryEntity = (GrailsHibernatePersistentEntity) subqueryCriteria.getPersistentEntity();
        Subquery<?> subquery = criteriaQuery.subquery(subqueryEntity.getJavaClass());
        var creator = new JpaCriteriaQueryCreator(new Query.ProjectionList(), criteriaBuilder, subqueryEntity, (DetachedCriteria) subqueryCriteria, conversionService);
        creator.setParentContext(fromsByProvider);
        creator.populateSubquery((JpaSubQuery) subquery);
        return criteriaBuilder.exists(subquery);
    }

    /**
     * Applies a deferred {@code whereLazy}/{@code buildLazy} closure before a criteria that is only
     * used as a subquery value is translated. The executed root criteria is materialized by
     * {@code DetachedCriteria}, but a criteria reached only through a subquery criterion never is,
     * so its restrictions would be silently dropped.
     */
    private static void materializeLazyCriteria(QueryableCriteria<?> criteria) {
        if (criteria instanceof DetachedCriteria<?> detachedCriteria) {
            detachedCriteria.applyLazyCriteria();
        }
    }

    private Object convertValue(GrailsHibernatePersistentEntity entity, String propertyName, Object value, Expression<?> propertyPath) {
        if (value == null) {
            throw new ConfigurationException("Null value for property [" + propertyName + "] is not allowed in comparison criteria.");
        }

        Class<?> targetType = propertyPath != null ? propertyPath.getJavaType() : null;

        if (targetType == null && entity != null) {
            HibernatePersistentProperty prop = (HibernatePersistentProperty) entity.getPropertyByName(propertyName);
            if (prop != null) {
                targetType = prop.getType();
            }
        }

        if (targetType != null && Collection.class.isAssignableFrom(targetType) && entity != null) {
            HibernatePersistentProperty prop = (HibernatePersistentProperty) entity.getPropertyByName(propertyName);
            if (prop instanceof HibernateToManyProperty toMany) {
                targetType = toMany.getComponentType();
            }
        }

        if (targetType != null && conversionService.canConvert(value.getClass(), targetType)) {
            if (!Collection.class.isAssignableFrom(targetType) || Collection.class.isAssignableFrom(value.getClass())) {
                return conversionService.convert(value, targetType);
            }
        }
        return value;
    }

    /**
     * Returns an arithmetic expression for a {@link PropertyArithmetic}, otherwise the converted value itself.
     * A plain value is not wrapped in a literal, so the comparison overload that takes it infers its type from
     * the property path, as HQL does for a parameter. A literal is typed from the value's class instead, which a
     * property mapped with a custom {@code UserType} cannot be compared with when that class is
     * {@link java.io.Serializable}.
     */
    @SuppressWarnings("unchecked")
    private Object convertComparisonValue(GrailsHibernatePersistentEntity entity, String propertyName, Object value, JpaQueryContext context, Expression<?> propertyPath) {
        if (value instanceof PropertyArithmetic pa) {
            Expression<Number> left = (Expression<Number>) context.getFullyQualifiedExpression(pa.propertyName());
            Expression<Number> right = (Expression<Number>) criteriaBuilder.literal(pa.operand());

            return switch (pa.operator()) {
                case MULTIPLY -> criteriaBuilder.prod(left, right);
                case DIVIDE -> criteriaBuilder.quot(left, right);
                case ADD -> criteriaBuilder.sum(left, right);
                case SUBTRACT -> criteriaBuilder.diff(left, right);
            };
        }
        return convertValue(entity, propertyName, value, propertyPath);
    }

    /**
     * Returns the value as an expression for a comparison overload that takes an expression, which the JPA ordering
     * overloads need for a value that is not {@link Comparable}. The value becomes a criteria parameter of the
     * property's Java type, bound once the query is created, so Hibernate types it from the property it is compared
     * with, as it does for an HQL parameter.
     */
    private Expression<?> asExpression(Object value, Expression<?> propertyPath, JpaQueryContext context) {
        if (value instanceof Expression<?> expression) {
            return expression;
        }
        ParameterExpression<?> parameter = criteriaBuilder.parameter(propertyPath.getJavaType());
        context.bindParameter(parameter, value);
        return parameter;
    }

    public Predicate generate(
        AbstractQuery<?> cq, From<?, ?> root, List<Query.Criterion> criteriaList, JpaQueryContext tablesByName, GrailsHibernatePersistentEntity entity) {
        Predicate[] predicates = getPredicates(cq, root, criteriaList, tablesByName, entity);
        return criteriaBuilder.and(predicates);
    }
}
