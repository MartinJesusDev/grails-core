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
package org.grails.datastore.mapping.query.jpa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

import org.grails.datastore.mapping.core.exceptions.ConfigurationException;
import org.grails.datastore.mapping.model.AbstractPersistentEntity;
import org.grails.datastore.mapping.model.PersistentEntity;
import org.grails.datastore.mapping.model.PersistentProperty;
import org.grails.datastore.mapping.model.types.Association;
import org.grails.datastore.mapping.model.types.ToOne;
import org.grails.datastore.mapping.query.AssociationQuery;
import org.grails.datastore.mapping.query.Query;
import org.grails.datastore.mapping.query.api.AssociationCriteria;
import org.grails.datastore.mapping.query.api.QueryableCriteria;
import org.grails.datastore.mapping.query.criteria.FunctionCallingCriterion;

/**
 * Builds JPA 1.0 String-based queries from the Query model
 *
 * @author Graeme Rocher
 * @since 1.0
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class JpaQueryBuilder {
    private static final String DISTINCT_CLAUSE = "DISTINCT ";
    private static final String SELECT_CLAUSE = "SELECT ";
    private static final String AS_CLAUSE = " AS ";
    private static final String FROM_CLAUSE = " FROM ";
    private static final String ORDER_BY_CLAUSE = " ORDER BY ";
    private static final String WHERE_CLAUSE = " WHERE ";
    private static final char COMMA = ',';
    private static final char CLOSE_BRACKET = ')';
    private static final char OPEN_BRACKET = '(';
    private static final char SPACE = ' ';
    private static final char QUESTIONMARK = '?';
    private static final char DOT = '.';
    public static final String NOT_CLAUSE = " NOT";
    public static final String LOGICAL_AND = " AND ";
    public static final String UPDATE_CLAUSE = "UPDATE ";
    public static final String DELETE_CLAUSE = "DELETE FROM ";

    public static final String LOGICAL_OR = " OR ";
    private static final String ALIAS_PLACEHOLDER = "{alias}";

    private final Map<Class, QueryHandler> queryHandlers = new HashMap<>();
    private Map<String, String> aliases = new HashMap<>();
    private String correlationLogicalName;
    private PersistentEntity correlationEntity;
    public static final String PARAMETER_NAME_PREFIX = "p";
    private static final String PARAMETER_PREFIX = ":p";
    private PersistentEntity entity;
    private Query.Junction criteria;
    private Query.ProjectionList projectionList = new Query.ProjectionList();
    private List<Query.Order> orders = Collections.emptyList();
    private String logicalName;
    private ConversionService conversionService = new GenericConversionService();
    private boolean hibernateCompatible;

    public JpaQueryBuilder(QueryableCriteria criteria) {
        this(criteria.getPersistentEntity(), materializedCriteria(criteria));
    }

    private static List<Query.Criterion> materializedCriteria(QueryableCriteria criteria) {
        return criteria.materializeLazyCriteria().getCriteria();
    }

    public JpaQueryBuilder(PersistentEntity entity, List<Query.Criterion> criteria) {
        this(entity, new Query.Conjunction(criteria));
    }

    public JpaQueryBuilder(PersistentEntity entity, List<Query.Criterion> criteria, Query.ProjectionList projectionList) {
        this(entity, new Query.Conjunction(criteria), projectionList);
    }

    public JpaQueryBuilder(PersistentEntity entity, List<Query.Criterion> criteria, Query.ProjectionList projectionList, List<Query.Order> orders) {
        this(entity, new Query.Conjunction(criteria), projectionList, orders);
    }

    public JpaQueryBuilder(PersistentEntity entity, Query.Junction criteria) {
        if (entity == null) {
            throw new ConfigurationException("No persistent entity specified for JPA query builder");
        }
        this.entity = entity;
        this.criteria = criteria;
        this.logicalName = entity.getDecapitalizedName();
    }

    public JpaQueryBuilder(PersistentEntity entity, Query.Junction criteria, Query.ProjectionList projectionList) {
        this(entity, criteria);
        this.projectionList = projectionList;
    }

    public JpaQueryBuilder(PersistentEntity entity, Query.Junction criteria, Query.ProjectionList projectionList, List<Query.Order> orders) {
        this(entity, criteria, projectionList);
        this.orders = orders;
    }

    public void setHibernateCompatible(boolean hibernateCompatible) {
        this.hibernateCompatible = hibernateCompatible;
    }

    public void setConversionService(ConversionService conversionService) {
        this.conversionService = conversionService;
    }

    /**
     * Builds an UPDATE statement.
     *
     * @param propertiesToUpdate THe properties to update
     * @return The JpaQueryInfo object
     */
    public JpaQueryInfo buildUpdate(Map<String, Object> propertiesToUpdate) {
        if (propertiesToUpdate.isEmpty()) {
            throw new InvalidDataAccessResourceUsageException("No properties specified to update");
        }
        StringBuilder queryString = new StringBuilder(UPDATE_CLAUSE).append(entity.getName()).append(SPACE).append(logicalName);

        List parameters = new ArrayList();
        buildUpdateStatement(queryString, propertiesToUpdate, parameters, hibernateCompatible);
        StringBuilder whereClause = new StringBuilder();
        collectAliases(criteria.getCriteria());
        buildWhereClause(entity, criteria, queryString, whereClause, logicalName, false, parameters);
        return new JpaQueryInfo(queryString.toString(), parameters);
    }

    /**
     * Builds a DELETE statement
     *
     * @return The JpaQueryInfo
     */
    public JpaQueryInfo buildDelete() {
        StringBuilder queryString = new StringBuilder(DELETE_CLAUSE).append(entity.getName()).append(SPACE).append(logicalName);
        StringBuilder whereClause = new StringBuilder();
        collectAliases(criteria.getCriteria());
        List parameters = buildWhereClause(entity, criteria, queryString, whereClause, logicalName, false);
        return new JpaQueryInfo(queryString.toString(), parameters);
    }

    /**
     * Builds  SELECT statement
     *
     * @return The JpaQueryInfo
     */
    public JpaQueryInfo buildSelect() {
        StringBuilder queryString = new StringBuilder(SELECT_CLAUSE);

        buildSelectClause(queryString);

        StringBuilder whereClause = new StringBuilder();
        List parameters = null;
        if (!criteria.isEmpty()) {
            parameters = buildWhereClause(entity, criteria, queryString, whereClause, logicalName, true);
        }

        appendOrder(queryString, logicalName);
        return new JpaQueryInfo(queryString.toString(), parameters);
    }

    private void buildSelectClause(StringBuilder queryString) {
        Query.ProjectionList projectionList = this.projectionList;
        String logicalName = this.logicalName;
        PersistentEntity entity = this.entity;
        buildSelect(queryString, projectionList.getProjectionList(), logicalName, entity);

        queryString.append(FROM_CLAUSE)
                .append(entity.getName())
                .append(AS_CLAUSE)
                .append(logicalName);
    }

    private static void buildSelect(StringBuilder queryString, List<Query.Projection> projectionList, String logicalName, PersistentEntity entity) {
        if (projectionList.isEmpty()) {
            queryString.append(DISTINCT_CLAUSE)
                    .append(logicalName);
        }
        else {
            for (Iterator i = projectionList.iterator(); i.hasNext();) {
                Query.Projection projection = (Query.Projection) i.next();
                if (projection instanceof Query.CountProjection) {
                    queryString.append("COUNT(")
                            .append(logicalName)
                            .append(CLOSE_BRACKET);
                }
                else if (projection instanceof Query.IdProjection) {
                    queryString.append(logicalName)
                            .append(DOT)
                            .append(entity.getIdentity().getName());
                }
                else if (projection instanceof Query.PropertyProjection) {
                    Query.PropertyProjection pp = (Query.PropertyProjection) projection;
                    if (projection instanceof Query.AvgProjection) {
                        queryString.append("AVG(")
                                .append(logicalName)
                                .append(DOT)
                                .append(pp.getPropertyName())
                                .append(CLOSE_BRACKET);
                    }
                    else if (projection instanceof Query.SumProjection) {
                        queryString.append("SUM(")
                                .append(logicalName)
                                .append(DOT)
                                .append(pp.getPropertyName())
                                .append(CLOSE_BRACKET);
                    }
                    else if (projection instanceof Query.MinProjection) {
                        queryString.append("MIN(")
                                .append(logicalName)
                                .append(DOT)
                                .append(pp.getPropertyName())
                                .append(CLOSE_BRACKET);
                    }
                    else if (projection instanceof Query.MaxProjection) {
                        queryString.append("MAX(")
                                .append(logicalName)
                                .append(DOT)
                                .append(pp.getPropertyName())
                                .append(CLOSE_BRACKET);
                    }
                    else if (projection instanceof Query.CountDistinctProjection) {
                        queryString.append("COUNT(DISTINCT ")
                                .append(logicalName)
                                .append(DOT)
                                .append(pp.getPropertyName())
                                .append(CLOSE_BRACKET);
                    }
                    else {
                        queryString.append(logicalName)
                                .append(DOT)
                                .append(pp.getPropertyName());
                    }
                }

                if (i.hasNext()) {
                    queryString.append(COMMA);
                }
            }
        }
    }

    public int appendCriteriaForOperator(StringBuilder q,
                                                String logicalName, final String name, int position, String operator, boolean hibernateCompatible) {
        q.append(resolvePropertyPath(logicalName, name))
            .append(operator)
            .append(PARAMETER_PREFIX)
            .append(++position);
        return position;
    }

    /**
     * Renders a comparison whose right side is a subquery, for example
     * {@code age > (select avg(age) from Person)}. The subquery may refer to the enclosing query
     * through the {alias} placeholder.
     */
    private int appendSubqueryComparison(StringBuilder q, StringBuilder whereClause, String logicalName, String name, String operator, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible, QueryableCriteria subquery, PersistentEntity entity) {
        whereClause.append(resolvePropertyPath(logicalName, name))
                .append(operator)
                .append(OPEN_BRACKET);
        position = buildSubQuery(q, whereClause, position, parameters, conversionService, allowJoins, hibernateCompatible, subquery, logicalName, entity);
        whereClause.append(CLOSE_BRACKET);
        return position;
    }

    {

        queryHandlers.put(AssociationQuery.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion,
                              StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters,
                              ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {

                if (!allowJoins) {
                    throw new InvalidDataAccessResourceUsageException("Joins cannot be used in a DELETE or UPDATE operation");
                }
                AssociationQuery aq = (AssociationQuery) criterion;
                final Association<?> association = aq.getAssociation();
                Query.Junction associationCriteria = aq.getCriteria();
                List<Query.Criterion> associationCriteriaList = associationCriteria.getCriteria();

                return handleAssociationCriteria(q, whereClause, logicalName, position, parameters, conversionService, allowJoins, association, associationCriteria, associationCriteriaList, hibernateCompatible);
            }
        });

        queryHandlers.put(Query.Negation.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion,
                              StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters,
                              ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {

                whereClause.append(NOT_CLAUSE)
                           .append(OPEN_BRACKET);

                final Query.Negation negation = (Query.Negation) criterion;
                position = buildWhereClauseForCriterion(entity, negation, q, whereClause, logicalName, negation.getCriteria(), position, parameters, conversionService, allowJoins, hibernateCompatible);
                whereClause.append(CLOSE_BRACKET);

                return position;
            }
        });

        queryHandlers.put(Query.Conjunction.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion,
                              StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters,
                              ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                whereClause.append(OPEN_BRACKET);

                final Query.Conjunction conjunction = (Query.Conjunction) criterion;
                position = buildWhereClauseForCriterion(entity, conjunction, q, whereClause, logicalName, conjunction.getCriteria(), position, parameters, conversionService, allowJoins, hibernateCompatible);
                whereClause.append(CLOSE_BRACKET);

                return position;
            }
        });

        queryHandlers.put(Query.Disjunction.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion,
                              StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters,
                              ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                whereClause.append(OPEN_BRACKET);

                final Query.Disjunction disjunction = (Query.Disjunction) criterion;
                position = buildWhereClauseForCriterion(entity, disjunction, q, whereClause, logicalName, disjunction.getCriteria(), position, parameters, conversionService, allowJoins, hibernateCompatible);
                whereClause.append(CLOSE_BRACKET);

                return position;
            }
        });

        queryHandlers.put(Query.Equals.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.Equals eq = (Query.Equals) criterion;
                final String name = eq.getProperty();
                PersistentProperty prop = validateProperty(entity, name, Query.Equals.class);
                Class propType = prop.getType();
                position = appendCriteriaForOperator(whereClause, logicalName, name, position, "=", hibernateCompatible);
                parameters.add(conversionService.convert(eq.getValue(), propType));
                return position;
            }
        });

        queryHandlers.put(Query.EqualsProperty.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.EqualsProperty eq = (Query.EqualsProperty) criterion;
                final String propertyName = eq.getProperty();
                String otherProperty = eq.getOtherProperty();

                validateProperty(entity, propertyName, Query.EqualsProperty.class);
                validateProperty(entity, otherProperty, Query.EqualsProperty.class);
                appendPropertyComparison(whereClause, logicalName, propertyName, otherProperty, "=");
                return position;
            }
        });

        queryHandlers.put(Query.NotEqualsProperty.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.PropertyComparisonCriterion eq = (Query.PropertyComparisonCriterion) criterion;
                final String propertyName = eq.getProperty();
                String otherProperty = eq.getOtherProperty();

                validateProperty(entity, propertyName, Query.NotEqualsProperty.class);
                validateProperty(entity, otherProperty, Query.NotEqualsProperty.class);
                appendPropertyComparison(whereClause, logicalName, propertyName, otherProperty, "!=");
                return position;
            }
        });

        queryHandlers.put(Query.GreaterThanProperty.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.PropertyComparisonCriterion eq = (Query.PropertyComparisonCriterion) criterion;
                final String propertyName = eq.getProperty();
                String otherProperty = eq.getOtherProperty();

                validateProperty(entity, propertyName, Query.GreaterThanProperty.class);
                validateProperty(entity, otherProperty, Query.GreaterThanProperty.class);
                appendPropertyComparison(whereClause, logicalName, propertyName, otherProperty, ">");
                return position;
            }
        });

        queryHandlers.put(Query.GreaterThanEqualsProperty.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.PropertyComparisonCriterion eq = (Query.PropertyComparisonCriterion) criterion;
                final String propertyName = eq.getProperty();
                String otherProperty = eq.getOtherProperty();

                validateProperty(entity, propertyName, Query.GreaterThanEqualsProperty.class);
                validateProperty(entity, otherProperty, Query.GreaterThanEqualsProperty.class);
                appendPropertyComparison(whereClause, logicalName, propertyName, otherProperty, ">=");
                return position;
            }
        });

        queryHandlers.put(Query.LessThanProperty.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.PropertyComparisonCriterion eq = (Query.PropertyComparisonCriterion) criterion;
                final String propertyName = eq.getProperty();
                String otherProperty = eq.getOtherProperty();

                validateProperty(entity, propertyName, Query.LessThanProperty.class);
                validateProperty(entity, otherProperty, Query.LessThanProperty.class);
                appendPropertyComparison(whereClause, logicalName, propertyName, otherProperty, "<");
                return position;
            }
        });

        queryHandlers.put(Query.LessThanEqualsProperty.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.PropertyComparisonCriterion eq = (Query.PropertyComparisonCriterion) criterion;
                final String propertyName = eq.getProperty();
                String otherProperty = eq.getOtherProperty();

                validateProperty(entity, propertyName, Query.LessThanEqualsProperty.class);
                validateProperty(entity, otherProperty, Query.LessThanEqualsProperty.class);
                appendPropertyComparison(whereClause, logicalName, propertyName, otherProperty, "<=");
                return position;
            }
        });

        queryHandlers.put(Query.IsNull.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.IsNull isNull = (Query.IsNull) criterion;
                final String name = isNull.getProperty();
                validateProperty(entity, name, Query.IsNull.class);
                whereClause.append(resolvePropertyPath(logicalName, name))
                           .append(" IS NULL ");

                return position;
            }
        });

        queryHandlers.put(Query.IsNotNull.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.IsNotNull isNotNull = (Query.IsNotNull) criterion;
                final String name = isNotNull.getProperty();
                validateProperty(entity, name, Query.IsNotNull.class);
                whereClause.append(resolvePropertyPath(logicalName, name))
                           .append(" IS NOT NULL ");

                return position;
            }
        });

        queryHandlers.put(Query.IsEmpty.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.IsEmpty isEmpty = (Query.IsEmpty) criterion;
                final String name = isEmpty.getProperty();
                validateProperty(entity, name, Query.IsEmpty.class);
                whereClause.append(resolvePropertyPath(logicalName, name))
                           .append(" IS EMPTY ");

                return position;
            }
        });

        queryHandlers.put(Query.IsNotEmpty.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.IsNotEmpty isNotEmpty = (Query.IsNotEmpty) criterion;
                final String name = isNotEmpty.getProperty();
                validateProperty(entity, name, Query.IsNotEmpty.class);
                whereClause.append(resolvePropertyPath(logicalName, name))
                           .append(" IS NOT EMPTY ");

                return position;
            }
        });

        queryHandlers.put(Query.IdEquals.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.IdEquals eq = (Query.IdEquals) criterion;
                PersistentProperty prop = entity.getIdentity();
                Class propType = prop.getType();
                position = appendCriteriaForOperator(whereClause, logicalName, prop.getName(), position, "=", hibernateCompatible);
                parameters.add(conversionService.convert(eq.getValue(), propType));
                return position;
            }
        });

        queryHandlers.put(Query.NotEquals.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.NotEquals eq = (Query.NotEquals) criterion;
                final String name = eq.getProperty();
                PersistentProperty prop = validateProperty(entity, name, Query.NotEquals.class);
                Class propType = prop.getType();
                position = appendCriteriaForOperator(whereClause, logicalName, name, position, " != ", hibernateCompatible);
                parameters.add(conversionService.convert(eq.getValue(), propType));
                return position;
            }
        });

        queryHandlers.put(Query.GreaterThan.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.GreaterThan eq = (Query.GreaterThan) criterion;
                final String name = eq.getProperty();
                PersistentProperty prop = validateProperty(entity, name, Query.GreaterThan.class);
                Class propType = prop.getType();
                if (eq.getValue() instanceof QueryableCriteria subquery) {
                    return appendSubqueryComparison(q, whereClause, logicalName, name, " > ", position, parameters, conversionService, allowJoins, hibernateCompatible, subquery, entity);
                }
                position = appendCriteriaForOperator(whereClause, logicalName, name, position, " > ", hibernateCompatible);
                parameters.add(conversionService.convert(eq.getValue(), propType));
                return position;
            }
        });

        queryHandlers.put(Query.LessThanEquals.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.LessThanEquals eq = (Query.LessThanEquals) criterion;
                final String name = eq.getProperty();
                PersistentProperty prop = validateProperty(entity, name, Query.LessThanEquals.class);
                Class propType = prop.getType();
                if (eq.getValue() instanceof QueryableCriteria subquery) {
                    return appendSubqueryComparison(q, whereClause, logicalName, name, " <= ", position, parameters, conversionService, allowJoins, hibernateCompatible, subquery, entity);
                }
                position = appendCriteriaForOperator(whereClause, logicalName, name, position, " <= ", hibernateCompatible);
                parameters.add(conversionService.convert(eq.getValue(), propType));
                return position;
            }
        });

        queryHandlers.put(Query.GreaterThanEquals.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.GreaterThanEquals eq = (Query.GreaterThanEquals) criterion;
                final String name = eq.getProperty();
                PersistentProperty prop = validateProperty(entity, name, Query.GreaterThanEquals.class);
                Class propType = prop.getType();
                if (eq.getValue() instanceof QueryableCriteria subquery) {
                    return appendSubqueryComparison(q, whereClause, logicalName, name, " >= ", position, parameters, conversionService, allowJoins, hibernateCompatible, subquery, entity);
                }
                position = appendCriteriaForOperator(whereClause, logicalName, name, position, " >= ", hibernateCompatible);
                parameters.add(conversionService.convert(eq.getValue(), propType));
                return position;
            }
        });

        queryHandlers.put(Query.Between.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.Between between = (Query.Between) criterion;
                final Object from = between.getFrom();
                final Object to = between.getTo();

                final String name = between.getProperty();
                PersistentProperty prop = validateProperty(entity, name, Query.Between.class);
                Class propType = prop.getType();
                final String qualifiedName = logicalName + DOT + name;
                whereClause.append(OPEN_BRACKET)
                           .append(qualifiedName)
                           .append(" >= ")
                           .append(PARAMETER_PREFIX)
                           .append(++position);
                whereClause.append(" AND ")
                           .append(qualifiedName)
                           .append(" <= ")
                           .append(PARAMETER_PREFIX)
                           .append(++position)
                           .append(CLOSE_BRACKET);

                parameters.add(conversionService.convert(from, propType));
                parameters.add(conversionService.convert(to, propType));
                return position;
            }
        });

        queryHandlers.put(Query.LessThan.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.LessThan eq = (Query.LessThan) criterion;
                final String name = eq.getProperty();
                PersistentProperty prop = validateProperty(entity, name, Query.LessThan.class);
                Class propType = prop.getType();
                if (eq.getValue() instanceof QueryableCriteria subquery) {
                    return appendSubqueryComparison(q, whereClause, logicalName, name, " < ", position, parameters, conversionService, allowJoins, hibernateCompatible, subquery, entity);
                }
                position = appendCriteriaForOperator(whereClause, logicalName, name, position, " < ", hibernateCompatible);
                parameters.add(conversionService.convert(eq.getValue(), propType));
                return position;
            }
        });

        queryHandlers.put(Query.Like.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.Like eq = (Query.Like) criterion;
                final String name = eq.getProperty();
                PersistentProperty prop = validateProperty(entity, name, Query.Like.class);
                Class propType = prop.getType();
                position = appendCriteriaForOperator(whereClause, logicalName, name, position, " like ", hibernateCompatible);
                parameters.add(conversionService.convert(eq.getValue(), propType));
                return position;
            }
        });

        queryHandlers.put(Query.ILike.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.ILike eq = (Query.ILike) criterion;
                final String name = eq.getProperty();
                PersistentProperty prop = validateProperty(entity, name, Query.ILike.class);
                Class propType = prop.getType();
                whereClause.append("lower(")
                    .append(resolvePropertyPath(logicalName, name))
                    .append(")")
                    .append(" like lower(")
                    .append(PARAMETER_PREFIX)
                    .append(++position)
                    .append(")");
                parameters.add(conversionService.convert(eq.getValue(), propType));
                return position;
            }
        });

        queryHandlers.put(FunctionCallingCriterion.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                FunctionCallingCriterion functionCriterion = (FunctionCallingCriterion) criterion;
                String name = functionCriterion.getProperty();
                validateProperty(entity, name, FunctionCallingCriterion.class);
                if (functionCriterion.isOnValue()) {
                    throw new InvalidDataAccessResourceUsageException("A function on the value of a comparison is not supported: " + functionCriterion.getFunctionName());
                }
                Query.PropertyCriterion wrapped = functionCriterion.getPropertyCriterion();
                whereClause.append(functionCriterion.getFunctionName())
                           .append(OPEN_BRACKET)
                           .append(resolvePropertyPath(logicalName, name))
                           .append(CLOSE_BRACKET);
                if (wrapped instanceof Query.In in) {
                    whereClause.append(" IN (");
                    for (Iterator i = in.getValues().iterator(); i.hasNext();) {
                        whereClause.append(PARAMETER_PREFIX).append(++position);
                        if (i.hasNext()) {
                            whereClause.append(COMMA);
                        }
                        parameters.add(i.next());
                    }
                    whereClause.append(CLOSE_BRACKET);
                }
                else {
                    String operator = functionOperator(wrapped);
                    if (operator == null) {
                        throw new InvalidDataAccessResourceUsageException("Unsupported criterion in a function call: " + wrapped.getClass().getSimpleName());
                    }
                    whereClause.append(operator).append(PARAMETER_PREFIX).append(++position);
                    parameters.add(wrapped.getValue());
                }
                return position;
            }
        });

        queryHandlers.put(Query.In.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.In inQuery = (Query.In) criterion;
                final String name = inQuery.getProperty();
                PersistentProperty prop = validateProperty(entity, name, Query.In.class);
                Class propType = prop.getType();
                whereClause.append(resolvePropertyPath(logicalName, name))
                           .append(" IN (");
                QueryableCriteria subquery = inQuery.getSubquery();
                if (subquery != null) {
                    position = buildSubQuery(q, whereClause, position, parameters, conversionService, allowJoins, hibernateCompatible, subquery, logicalName, entity);
                }
                else {
                    for (Iterator i = inQuery.getValues().iterator(); i.hasNext();) {
                        Object val = i.next();
                        whereClause.append(PARAMETER_PREFIX);
                        whereClause.append(++position);
                        if (i.hasNext()) {
                            whereClause.append(COMMA);
                        }
                        parameters.add(conversionService.convert(val, propType));
                    }
                }
                whereClause.append(CLOSE_BRACKET);

                return position;
            }
        });

        queryHandlers.put(Query.NotIn.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.NotIn notIn = (Query.NotIn) criterion;
                String comparisonExpression = " NOT IN (";
                return handleSubQuery(entity, q, whereClause, logicalName, position, parameters, conversionService, allowJoins, hibernateCompatible, notIn, comparisonExpression);
            }
        });

        queryHandlers.put(Query.Exists.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.Exists exists = (Query.Exists) criterion;
                whereClause.append("EXISTS (");
                position = buildSubQuery(q, whereClause, position, parameters, conversionService, allowJoins, hibernateCompatible, exists.getSubquery(), logicalName, entity);
                whereClause.append(CLOSE_BRACKET);
                return position;
            }
        });

        queryHandlers.put(Query.NotExists.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.NotExists notExists = (Query.NotExists) criterion;
                whereClause.append("NOT EXISTS (");
                position = buildSubQuery(q, whereClause, position, parameters, conversionService, allowJoins, hibernateCompatible, notExists.getSubquery(), logicalName, entity);
                whereClause.append(CLOSE_BRACKET);
                return position;
            }
        });

        queryHandlers.put(Query.EqualsAll.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.EqualsAll equalsAll = (Query.EqualsAll) criterion;
                String comparisonExpression = " = ALL (";
                return handleSubQuery(entity, q, whereClause, logicalName, position, parameters, conversionService, allowJoins, hibernateCompatible, equalsAll, comparisonExpression);
            }
        });

        queryHandlers.put(Query.NotEqualsAll.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.SubqueryCriterion equalsAll = (Query.SubqueryCriterion) criterion;
                String comparisonExpression = " != ALL (";
                return handleSubQuery(entity, q, whereClause, logicalName, position, parameters, conversionService, allowJoins, hibernateCompatible, equalsAll, comparisonExpression);
            }
        });

        queryHandlers.put(Query.GreaterThanAll.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.SubqueryCriterion equalsAll = (Query.SubqueryCriterion) criterion;
                String comparisonExpression = " > ALL (";
                return handleSubQuery(entity, q, whereClause, logicalName, position, parameters, conversionService, allowJoins, hibernateCompatible, equalsAll, comparisonExpression);
            }
        });

        queryHandlers.put(Query.GreaterThanSome.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.SubqueryCriterion equalsAll = (Query.SubqueryCriterion) criterion;
                String comparisonExpression = " > SOME (";
                return handleSubQuery(entity, q, whereClause, logicalName, position, parameters, conversionService, allowJoins, hibernateCompatible, equalsAll, comparisonExpression);
            }
        });

        queryHandlers.put(Query.GreaterThanEqualsAll.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.SubqueryCriterion equalsAll = (Query.SubqueryCriterion) criterion;
                String comparisonExpression = " >= ALL (";
                return handleSubQuery(entity, q, whereClause, logicalName, position, parameters, conversionService, allowJoins, hibernateCompatible, equalsAll, comparisonExpression);
            }
        });

        queryHandlers.put(Query.GreaterThanEqualsSome.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.SubqueryCriterion equalsAll = (Query.SubqueryCriterion) criterion;
                String comparisonExpression = " >= SOME (";
                return handleSubQuery(entity, q, whereClause, logicalName, position, parameters, conversionService, allowJoins, hibernateCompatible, equalsAll, comparisonExpression);
            }
        });

        queryHandlers.put(Query.LessThanAll.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.SubqueryCriterion subqueryCriterion = (Query.SubqueryCriterion) criterion;
                String comparisonExpression = " < ALL (";
                return handleSubQuery(entity, q, whereClause, logicalName, position, parameters, conversionService, allowJoins, hibernateCompatible, subqueryCriterion, comparisonExpression);
            }
        });

        queryHandlers.put(Query.LessThanSome.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.SubqueryCriterion subqueryCriterion = (Query.SubqueryCriterion) criterion;
                String comparisonExpression = " < SOME (";
                return handleSubQuery(entity, q, whereClause, logicalName, position, parameters, conversionService, allowJoins, hibernateCompatible, subqueryCriterion, comparisonExpression);
            }
        });

        queryHandlers.put(Query.LessThanEqualsAll.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.SubqueryCriterion subqueryCriterion = (Query.SubqueryCriterion) criterion;
                String comparisonExpression = " <= ALL (";
                return handleSubQuery(entity, q, whereClause, logicalName, position, parameters, conversionService, allowJoins, hibernateCompatible, subqueryCriterion, comparisonExpression);
            }
        });

        queryHandlers.put(Query.LessThanEqualsSome.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                Query.SubqueryCriterion subqueryCriterion = (Query.SubqueryCriterion) criterion;
                String comparisonExpression = " <= SOME (";
                return handleSubQuery(entity, q, whereClause, logicalName, position, parameters, conversionService, allowJoins, hibernateCompatible, subqueryCriterion, comparisonExpression);
            }
        });

        queryHandlers.put(Query.SizeEquals.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                return handleSizeComparison(entity, (Query.PropertyCriterion) criterion, whereClause, logicalName, position, parameters, conversionService, "=", hibernateCompatible);
            }
        });

        queryHandlers.put(Query.SizeNotEquals.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                return handleSizeComparison(entity, (Query.PropertyCriterion) criterion, whereClause, logicalName, position, parameters, conversionService, "!=", hibernateCompatible);
            }
        });

        queryHandlers.put(Query.SizeGreaterThan.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                return handleSizeComparison(entity, (Query.PropertyCriterion) criterion, whereClause, logicalName, position, parameters, conversionService, ">", hibernateCompatible);
            }
        });

        queryHandlers.put(Query.SizeGreaterThanEquals.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                return handleSizeComparison(entity, (Query.PropertyCriterion) criterion, whereClause, logicalName, position, parameters, conversionService, ">=", hibernateCompatible);
            }
        });

        queryHandlers.put(Query.SizeLessThan.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                return handleSizeComparison(entity, (Query.PropertyCriterion) criterion, whereClause, logicalName, position, parameters, conversionService, "<", hibernateCompatible);
            }
        });

        queryHandlers.put(Query.SizeLessThanEquals.class, new QueryHandler() {
            public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
                return handleSizeComparison(entity, (Query.PropertyCriterion) criterion, whereClause, logicalName, position, parameters, conversionService, "<=", hibernateCompatible);
            }
        });

    }

    protected int handleSubQuery(PersistentEntity entity, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible, Query.SubqueryCriterion equalsAll, String comparisonExpression) {
        final String name = equalsAll.getProperty();
        validateProperty(entity, name, Query.In.class);
        QueryableCriteria subquery = equalsAll.getValue();
        whereClause.append(resolvePropertyPath(logicalName, name))
                .append(comparisonExpression);
        position = buildSubQuery(q, whereClause, position, parameters, conversionService, allowJoins, hibernateCompatible, subquery, logicalName, entity);
        whereClause.append(CLOSE_BRACKET);
        return position;
    }

    protected int handleSizeComparison(PersistentEntity entity, Query.PropertyCriterion criterion, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, String operator, boolean hibernateCompatible) {
        final String name = criterion.getProperty();
        validateProperty(entity, name, criterion.getClass());
        Object value = criterion.getValue();
        int size = value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(value.toString());
        whereClause.append("SIZE(")
                   .append(resolvePropertyPath(logicalName, name))
                   .append(") ")
                   .append(operator)
                   .append(PARAMETER_PREFIX)
                   .append(++position);
        parameters.add(size);
        return position;
    }

    /**
     * Renders a subquery. {@code outerLogicalName} and {@code outerEntity} are the logical name and
     * the entity of the query that contains the subquery, which the {@code {alias}} placeholder
     * refers to. The subquery has its own alias scope, so the aliases collected so far are swapped
     * for the ones declared inside it while its criteria are rendered.
     */
    protected int buildSubQuery(StringBuilder q, StringBuilder whereClause, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible, QueryableCriteria subquery, String outerLogicalName, PersistentEntity outerEntity) {
        subquery = subquery.materializeLazyCriteria();
        PersistentEntity associatedEntity = subquery.getPersistentEntity();
        String associatedEntityName = associatedEntity.getName();
        String associatedEntityLogicalName = associatedEntity.getDecapitalizedName() + position;
        whereClause.append("SELECT ");
        buildSelect(whereClause, subquery.getProjections(), associatedEntityLogicalName, associatedEntity);
        whereClause.append(" FROM ")
                .append(associatedEntityName)
                .append(' ')
                .append(associatedEntityLogicalName);

        Map<String, String> previousAliases = aliases;
        String previousLogicalName = correlationLogicalName;
        PersistentEntity previousEntity = correlationEntity;
        aliases = new HashMap<>();
        correlationLogicalName = outerLogicalName;
        correlationEntity = outerEntity;
        try {
            List<Query.Criterion> criteria = subquery.getCriteria();
            collectAliases(criteria);
            StringBuilder subWhereClause = new StringBuilder();
            position = buildWhereClauseForCriterion(associatedEntity, new Query.Conjunction(criteria), q, subWhereClause, associatedEntityLogicalName, criteria, position, parameters, conversionService, allowJoins, hibernateCompatible);
            if (subWhereClause.length() > 0) {
                whereClause.append(" WHERE ").append(subWhereClause);
            }
        }
        finally {
            aliases = previousAliases;
            correlationLogicalName = previousLogicalName;
            correlationEntity = previousEntity;
        }
        return position;
    }

    /**
     * Collects the alias of every association criteria declared in this scope, so a criterion that
     * refers to the association through its alias resolves to the association path.
     */
    private void collectAliases(List<Query.Criterion> criteria) {
        for (Query.Criterion criterion : criteria) {
            if (criterion instanceof Query.Junction) {
                collectAliases(((Query.Junction) criterion).getCriteria());
            }
            else if (criterion instanceof AssociationCriteria) {
                AssociationCriteria associationCriteria = (AssociationCriteria) criterion;
                if (associationCriteria.getAlias() != null) {
                    aliases.put(associationCriteria.getAlias(), associationCriteria.getAssociationPath());
                }
                collectAliases(associationCriteria.getCriteria());
            }
        }
    }

    /**
     * Resolves the alias of an association path to the association path itself: with
     * {@code createAlias('parent', 'p')}, {@code p.id} becomes {@code parent.id}.
     */
    private String resolveAliasPath(String name) {
        int dot = name.indexOf('.');
        if (dot > -1) {
            String path = aliases.get(name.substring(0, dot));
            if (path != null) {
                return path + name.substring(dot);
            }
        }
        return name;
    }

    /**
     * Resolves the complete property path for rendering: the {@code {alias}} placeholder becomes the
     * logical name of the enclosing query, an association alias becomes its association path, and
     * every other path is prefixed with the logical name of the query being rendered.
     */
    private String resolvePropertyPath(String logicalName, String name) {
        if (name.startsWith(ALIAS_PLACEHOLDER + ".")) {
            return correlationLogicalName + name.substring(ALIAS_PLACEHOLDER.length());
        }
        return logicalName + DOT + resolveAliasPath(name);
    }

    private int handleAssociationCriteria(StringBuilder query, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, Association<?> association, Query.Junction associationCriteria, List<Query.Criterion> associationCriteriaList, boolean hibernateCompatible) {
        if (association instanceof ToOne) {
            final String associationName = association.getName();
            logicalName = logicalName + DOT + associationName;
            return buildWhereClauseForCriterion(association.getAssociatedEntity(), associationCriteria, query, whereClause, logicalName, associationCriteriaList, position, parameters, conversionService, allowJoins, hibernateCompatible);
        }

        if (association != null) {
            final String associationName = association.getName();
            // TODO: Allow customization of join strategy!
            String joinType = " INNER JOIN ";
            query.append(joinType)
                .append(logicalName)
                .append(DOT)
                .append(associationName)
                .append(SPACE)
                .append(associationName);

            return buildWhereClauseForCriterion(association.getAssociatedEntity(), associationCriteria, query, whereClause, associationName, associationCriteriaList, position, parameters, conversionService, allowJoins, hibernateCompatible);
        }

        return position;
    }

    private void buildUpdateStatement(StringBuilder queryString, Map<String, Object> propertiesToUpdate, List parameters, boolean hibernateCompatible) {
        queryString.append(SPACE).append("SET");

        // keys need to be sorted before query is built
        Set<String> keys = new TreeSet<>(propertiesToUpdate.keySet());

        Iterator<String> iterator = keys.iterator();
        while (iterator.hasNext()) {
            String propertyName = iterator.next();
            PersistentProperty prop = entity.getPropertyByName(propertyName);
            if (prop == null) throw new InvalidDataAccessResourceUsageException("Property '" + propertyName + "' of class '" + entity.getName() + "' specified in update does not exist");

            parameters.add(propertiesToUpdate.get(propertyName));
            queryString.append(SPACE).append(logicalName).append(DOT).append(propertyName).append('=');
            queryString.append(PARAMETER_PREFIX).append(parameters.size());
            if (iterator.hasNext()) {
                queryString.append(COMMA);
            }
        }
    }

    private void appendPropertyComparison(StringBuilder q, String logicalName, String propertyName, String otherProperty, String operator) {
        q.append(resolvePropertyPath(logicalName, propertyName))
            .append(operator)
            .append(resolvePropertyPath(logicalName, otherProperty));
    }

    private PersistentProperty validateProperty(PersistentEntity entity, String name, Class criterionType) {
        if (name.startsWith(ALIAS_PLACEHOLDER + ".")) {
            if (correlationEntity == null) {
                throw new InvalidDataAccessResourceUsageException("Cannot use [" + criterionType.getSimpleName() +
                        "] criterion with " + ALIAS_PLACEHOLDER + " outside of a subquery: " + name);
            }
            return validateProperty(correlationEntity, name.substring(ALIAS_PLACEHOLDER.length() + 1), criterionType);
        }
        String resolvedName = resolveAliasPath(name);
        PersistentProperty identity = entity.getIdentity();
        if (identity != null && identity.getName().equals(resolvedName)) {
            return identity;
        }
        if (resolvedName.indexOf('.') > -1) {
            return validateAssociationPath(entity, resolvedName, criterionType);
        }
        PersistentProperty[] compositeIdentity = ((AbstractPersistentEntity) entity).getCompositeIdentity();
        if (compositeIdentity != null) {
            for (PersistentProperty property : compositeIdentity) {
                if (property.getName().equals(resolvedName)) {
                    return property;
                }
            }
        }
        PersistentProperty prop = entity.getPropertyByName(resolvedName);
        if (prop == null) {
            throw new InvalidDataAccessResourceUsageException("Cannot use [" +
                  criterionType.getSimpleName() + "] criterion on non-existent property: " + name);
        }
        return prop;
    }

    /**
     * Resolves a dotted path through to-one associations, so a bulk write can filter by the id of
     * an association (the foreign key on the entity's own table) without a join. A path that walks
     * a to-many association, or a segment that is not a to-one association, is rejected.
     */
    private PersistentProperty validateAssociationPath(PersistentEntity entity, String name, Class criterionType) {
        String[] segments = name.split("\\.");
        PersistentEntity current = entity;
        for (int i = 0; i < segments.length - 1; i++) {
            PersistentProperty property = current.getPropertyByName(segments[i]);
            if (!(property instanceof ToOne) || ((Association) property).getAssociatedEntity() == null) {
                throw new InvalidDataAccessResourceUsageException("Cannot use [" +
                        criterionType.getSimpleName() + "] criterion on property path: " + name);
            }
            current = ((Association) property).getAssociatedEntity();
        }
        PersistentProperty last = current.getPropertyByName(segments[segments.length - 1]);
        if (last == null) {
            throw new InvalidDataAccessResourceUsageException("Cannot use [" +
                    criterionType.getSimpleName() + "] criterion on property path: " + name);
        }
        return last;
    }

    private static String functionOperator(Query.PropertyCriterion wrapped) {
        if (wrapped instanceof Query.Equals) {
            return " = ";
        }
        if (wrapped instanceof Query.NotEquals) {
            return " != ";
        }
        if (wrapped instanceof Query.GreaterThan) {
            return " > ";
        }
        if (wrapped instanceof Query.GreaterThanEquals) {
            return " >= ";
        }
        if (wrapped instanceof Query.LessThan) {
            return " < ";
        }
        if (wrapped instanceof Query.LessThanEquals) {
            return " <= ";
        }
        if (wrapped instanceof Query.ILike) {
            return " ilike ";
        }
        if (wrapped instanceof Query.Like) {
            return " like ";
        }
        return null;
    }

    private static interface QueryHandler {
        public int handle(PersistentEntity entity, Query.Criterion criterion, StringBuilder q, StringBuilder whereClause, String logicalName, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible);
    }

    private List buildWhereClause(PersistentEntity entity, Query.Junction criteria, StringBuilder q, StringBuilder whereClause, String logicalName, boolean allowJoins) {
        List parameters = new ArrayList();
        return buildWhereClause(entity, criteria, q, whereClause, logicalName, allowJoins, parameters);
    }

    private List buildWhereClause(PersistentEntity entity, Query.Junction criteria, StringBuilder q, StringBuilder whereClause, String logicalName, boolean allowJoins, List parameters) {
        if (!criteria.isEmpty()) {
            int position = parameters.size();
            final List<Query.Criterion> criterionList = criteria.getCriteria();
            whereClause.append(WHERE_CLAUSE);
            if (criteria instanceof Query.Negation) {
                whereClause.append(NOT_CLAUSE);
            }
            whereClause.append(OPEN_BRACKET);
            position = buildWhereClauseForCriterion(entity, criteria, q, whereClause, logicalName,
                    criterionList, position, parameters,
                    conversionService, allowJoins, this.hibernateCompatible);
            q.append(whereClause.toString());
            q.append(CLOSE_BRACKET);
        }
        return parameters;
    }

    protected void appendOrder(StringBuilder queryString, String logicalName) {
        if (!orders.isEmpty()) {
            queryString.append(ORDER_BY_CLAUSE);
            for (Query.Order order : orders) {
                queryString.append(logicalName)
                           .append(DOT)
                           .append(order.getProperty())
                           .append(SPACE)
                           .append(order.getDirection().toString())
                           .append(SPACE);
            }
        }
    }

    int buildWhereClauseForCriterion(PersistentEntity entity,
                                            Query.Junction criteria, StringBuilder q, StringBuilder whereClause, String logicalName,
                                            final List<Query.Criterion> criterionList, int position, List parameters, ConversionService conversionService, boolean allowJoins, boolean hibernateCompatible) {
        final String operator = criteria instanceof Query.Conjunction ? LOGICAL_AND : LOGICAL_OR;
        boolean rendered = false;
        for (Query.Criterion criterion : criterionList) {
            if (rendersNothing(criterion)) {
                continue;
            }
            if (rendered) {
                whereClause.append(operator);
            }
            QueryHandler qh = queryHandlers.get(criterion.getClass());
            if (qh != null) {

                position = qh.handle(entity, criterion, q, whereClause, logicalName,
                        position, parameters, conversionService, allowJoins, hibernateCompatible);
            }
            else if (criterion instanceof AssociationCriteria) {

                AssociationCriteria ac = (AssociationCriteria) criterion;
                Association association = ac.getAssociation();
                // A to-one association renders as a property path (logicalName.association.property), not a
                // join, so a bulk statement may filter through it; a to-many association needs a join, which
                // a DELETE or UPDATE cannot use.
                if (!allowJoins && !(association instanceof ToOne)) {
                    throw new InvalidDataAccessResourceUsageException("Joins cannot be used in a DELETE or UPDATE operation");
                }
                position = handleAssociationCriteria(q, whereClause, logicalName, position, parameters, conversionService, allowJoins, association, new Query.Conjunction(), ac.getCriteria(), hibernateCompatible);
            }
            else {
                throw new InvalidDataAccessResourceUsageException("Queries of type " + criterion.getClass().getSimpleName() + " are not supported by this implementation");
            }
            rendered = true;
        }

        return position;
    }

    /**
     * An association block without restrictions renders nothing: it only declares an alias, and the
     * join it may carry is not usable in a bulk statement.
     */
    private static boolean rendersNothing(Query.Criterion criterion) {
        if (criterion instanceof AssociationCriteria) {
            return ((AssociationCriteria) criterion).getCriteria().isEmpty();
        }
        if (criterion instanceof AssociationQuery) {
            return ((AssociationQuery) criterion).getCriteria().isEmpty();
        }
        return false;
    }
}
