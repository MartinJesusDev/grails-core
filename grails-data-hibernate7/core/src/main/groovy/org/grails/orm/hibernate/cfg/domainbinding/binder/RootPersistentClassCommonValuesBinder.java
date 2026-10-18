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
package org.grails.orm.hibernate.cfg.domainbinding.binder;

import jakarta.annotation.Nonnull;

import org.hibernate.boot.spi.InFlightMetadataCollector;
import org.hibernate.boot.spi.MetadataBuildingContext;
import java.util.HashMap;
import java.util.Map;

import org.hibernate.engine.spi.FilterDefinition;
import org.hibernate.mapping.RootClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.grails.orm.hibernate.cfg.CacheConfig;
import org.grails.orm.hibernate.cfg.Mapping;
import org.grails.orm.hibernate.cfg.PersistentEntityNamingStrategy;
import org.grails.orm.hibernate.cfg.domainbinding.hibernate.HibernatePersistentEntity;

public class RootPersistentClassCommonValuesBinder {

    public static final Logger LOG = LoggerFactory.getLogger(RootPersistentClassCommonValuesBinder.class);

    private final MetadataBuildingContext metadataBuildingContext;
    private final PersistentEntityNamingStrategy namingStrategy;
    private final IdentityBinder identityBinder;
    private final VersionBinder versionBinder;
    private final ClassBinder classBinder;
    private final ClassPropertiesBinder classPropertiesBinder;
    private final InFlightMetadataCollector mappings;

    public RootPersistentClassCommonValuesBinder(
            MetadataBuildingContext metadataBuildingContext,
            PersistentEntityNamingStrategy namingStrategy,
            IdentityBinder identityBinder,
            VersionBinder versionBinder,
            ClassBinder classBinder,
            ClassPropertiesBinder classPropertiesBinder,
            InFlightMetadataCollector mappings) {
        this.metadataBuildingContext = metadataBuildingContext;
        this.namingStrategy = namingStrategy;
        this.identityBinder = identityBinder;
        this.versionBinder = versionBinder;
        this.classBinder = classBinder;
        this.classPropertiesBinder = classPropertiesBinder;
        this.mappings = mappings;
    }


    /**
     * The SQL restriction an entity declares with the Hibernate annotation, so every query of the
     * entity carries it, like the annotation binder of Hibernate does.
     */
    private static void applySqlRestriction(HibernatePersistentEntity hibernatePersistentEntity, RootClass root) {
        Class<?> javaClass = hibernatePersistentEntity.getJavaClass();
        org.hibernate.annotations.SQLRestriction restriction = javaClass.getAnnotation(org.hibernate.annotations.SQLRestriction.class);
        if (restriction != null) {
            root.setWhere(restriction.value());
        }
    }

    /**
     * The filters an entity declares with the Hibernate annotations: the definition registers the
     * condition the session enables by name, with the types of its parameters, and the filter
     * applies it to the entity, which is what the annotation binder of Hibernate does.
     */

    private Map<String, org.hibernate.metamodel.mapping.JdbcMapping> resolveFilterParameters(
            org.hibernate.annotations.FilterDef definition, Class<?> javaClass) {
        org.hibernate.annotations.ParamDef[] parameters = definition.parameters();
        if (parameters.length == 0) {
            return Map.of();
        }
        var registry = metadataBuildingContext.getBootstrapContext().getTypeConfiguration().getBasicTypeRegistry();
        Map<String, org.hibernate.metamodel.mapping.JdbcMapping> parameterTypes = new HashMap<>();
        for (org.hibernate.annotations.ParamDef parameter : parameters) {
            var type = registry.getRegisteredType(parameter.type());
            if (type == null) {
                throw new IllegalArgumentException("Unknown type [" + parameter.type() + "] of the filter parameter ["
                        + parameter.name() + "] of [" + javaClass.getName() + "]");
            }
            parameterTypes.put(parameter.name(), type);
        }
        return parameterTypes;
    }

    private void applyFilters(HibernatePersistentEntity hibernatePersistentEntity, RootClass root) {
        Class<?> javaClass = hibernatePersistentEntity.getJavaClass();
        for (org.hibernate.annotations.FilterDef definition : javaClass.getAnnotationsByType(org.hibernate.annotations.FilterDef.class)) {
            if (!mappings.getFilterDefinitions().containsKey(definition.name())) {
                mappings.addFilterDefinition(new FilterDefinition(definition.name(), definition.defaultCondition(),
                        resolveFilterParameters(definition, javaClass)));
            }
        }
        for (org.hibernate.annotations.Filter filter : javaClass.getAnnotationsByType(org.hibernate.annotations.Filter.class)) {
            Map<String, String> aliasTableMap = new HashMap<>();
            Map<String, String> aliasEntityMap = new HashMap<>();
            for (org.hibernate.annotations.SqlFragmentAlias alias : filter.aliases()) {
                if (!alias.table().isEmpty()) {
                    aliasTableMap.put(alias.table(), alias.alias());
                }
                if (alias.entity() != void.class) {
                    aliasEntityMap.put(alias.entity().getName(), alias.alias());
                }
            }
            String condition = filter.condition();
            if (condition.isEmpty()) {
                FilterDefinition definition = mappings.getFilterDefinition(filter.name());
                condition = definition != null ? definition.getDefaultFilterCondition() : "";
            }
            root.addFilter(filter.name(), condition, filter.deduceAliasInjectionPoints(), aliasTableMap, aliasEntityMap);
        }
    }

    public RootClass bindRoot(@Nonnull HibernatePersistentEntity hibernatePersistentEntity) {

        RootClass root = new RootClass(this.metadataBuildingContext);
        classBinder.bindClass(hibernatePersistentEntity, root);
        applySqlRestriction(hibernatePersistentEntity, root);
        applyFilters(hibernatePersistentEntity, root);

        // get the schema and catalog names from the configuration
        Mapping gormMapping = hibernatePersistentEntity.getHibernateMappedForm();

        hibernatePersistentEntity.configureDerivedProperties();
        CacheConfig cc = gormMapping.getCache();
        if (cc != null && cc.getEnabled()) {
            root.setCacheConcurrencyStrategy(cc.getUsage().toString());
            root.setCached(true);
            if ("read-only".equalsIgnoreCase(cc.getUsage().toString())) {
                root.setMutable(false);
            }
            root.setLazyPropertiesCacheable(
                    !"non-lazy".equalsIgnoreCase(cc.getInclude().toString()));
        }

        var schema = hibernatePersistentEntity.getSchema(mappings);

        var catalog = hibernatePersistentEntity.getCatalog(mappings);

        // create the table
        var table = mappings.addTable(
                schema,
                catalog,
                hibernatePersistentEntity.getTableName(namingStrategy),
                null,
                hibernatePersistentEntity.isTableAbstract(),
                metadataBuildingContext,
                false);
        root.setTable(table);
        if (LOG.isDebugEnabled()) {
            LOG.debug("[GrailsDomainBinder] Mapping Grails domain class: {} -> {}", hibernatePersistentEntity.getName(), root.getTable().getName());
        }

        identityBinder.bindIdentity(hibernatePersistentEntity);
        versionBinder.bindVersion(hibernatePersistentEntity.getVersion(), root);
        root.createPrimaryKey();
        classPropertiesBinder.bindClassProperties(hibernatePersistentEntity);

        return root;
    }
}
