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
package grails.gorm.tests.inheritance

import grails.gorm.annotation.Entity
import grails.gorm.tests.HibernateGormDatastoreSpec

/**
 * The where query syntax and the bulk writes next to an inheritance hierarchy, next to the reads
 * the intermediate subclass query tests already cover. The sports car that shares its name with a
 * plain vehicle probes the discriminator: a bulk write of a subclass must not touch the rows of
 * the other types.
 */
class CriteriaInheritanceSpec extends HibernateGormDatastoreSpec {

    void setupSpec() {
        manager.registerDomainClasses(DocVehicle, DocCar, DocSportsCar, JoinVehicle, JoinCar, JoinSportsCar)
    }

    private void seedHierarchy() {
        new DocVehicle(name: 'vehicle').save(failOnError: true)
        new DocCar(name: 'car', doors: 2).save(failOnError: true)
        new DocSportsCar(name: 'sports car', doors: 4, topSpeed: 300).save(failOnError: true)
        new DocSportsCar(name: 'vehicle', doors: 4, topSpeed: 400).save(failOnError: true)
        manager.session.flush()
        manager.session.clear()
    }

    private void seedJoinedHierarchy() {
        new JoinVehicle(name: 'vehicle').save(failOnError: true)
        new JoinCar(name: 'car', doors: 2).save(failOnError: true)
        new JoinSportsCar(name: 'sports car', doors: 4, topSpeed: 300).save(failOnError: true)
        manager.session.flush()
        manager.session.clear()
    }

    void "the where syntax on the root returns the instances of every subclass"() {
        given:
        seedHierarchy()

        when:
        def all = DocVehicle.where {}.list()
        def byName = DocVehicle.where { name == 'vehicle' }.list()
        def cars = DocCar.where { doors == 2 }.list()*.name
        def sportsCars = DocSportsCar.where { topSpeed > 200 }.list()*.name.sort()

        then:
        all.collect { it.getClass().simpleName }.sort() == ['DocCar', 'DocSportsCar', 'DocSportsCar', 'DocVehicle']
        byName.collect { it.getClass().simpleName }.sort() == ['DocSportsCar', 'DocVehicle']
        cars == ['car']
        sportsCars == ['sports car', 'vehicle']
    }

    void "a polymorphic bulk delete selects the rows of every subclass"() {
        given:
        seedHierarchy()

        when: 'the root deletes every row that matches, whatever its type'
        Number deleted = DocVehicle.where { name == 'vehicle' }.deleteAll()
        manager.session.clear()

        then:
        deleted == 2
        DocVehicle.where {}.count() == 2

        when: 'the intermediate class deletes its own rows and the rows of its subclasses'
        Number deletedCars = DocCar.where {}.deleteAll()
        manager.session.clear()
        def remaining = DocVehicle.where {}.count()

        then: 'the car and the sports car are both rows of the intermediate class'
        deletedCars == 2
        remaining == 0
    }

    void "a subclass bulk delete leaves the rows of the other types alone"() {
        given:
        seedHierarchy()

        when:
        Number deleted = DocSportsCar.where { name == 'vehicle' }.deleteAll()
        manager.session.clear()
        def remaining = DocVehicle.where { name == 'vehicle' }.list()

        then: 'only the sports car is gone, the plain vehicle stays'
        deleted == 1
        remaining.collect { it.getClass().simpleName } == ['DocVehicle']
    }

    void "a subquery and an aggregate work across the hierarchy"() {
        given:
        seedHierarchy()

        when:
        def named = DocVehicle.where {
            name in DocSportsCar.where { projections { property('name') } }
        }.list()
        def aboveAverage = DocCar.where { doors > avg(doors) }.list()*.name.sort()

        then: 'the subquery supplies the names of the two sports cars and the outer query matches every row with them'
        named.collect { it.getClass().simpleName }.sort() == ['DocSportsCar', 'DocSportsCar', 'DocVehicle']
        named*.name.sort() == ['sports car', 'vehicle', 'vehicle']
        aboveAverage == ['sports car', 'vehicle']
    }

    void "the where syntax and a bulk delete work on a joined hierarchy"() {
        given:
        seedJoinedHierarchy()

        when:
        def all = JoinVehicle.where {}.list().collect { it.getClass().simpleName }.sort()
        Number deleted = JoinCar.where { doors == 2 }.deleteAll()
        manager.session.clear()

        then:
        all == ['JoinCar', 'JoinSportsCar', 'JoinVehicle']
        deleted == 1
        JoinVehicle.where {}.count() == 2
    }
}

@Entity
class DocVehicle {

    String name
}

@Entity
class DocCar extends DocVehicle {

    Integer doors
}

@Entity
class DocSportsCar extends DocCar {

    Integer topSpeed
}

@Entity
class JoinVehicle {

    String name

    static mapping = {
        tablePerHierarchy false
    }
}

@Entity
class JoinCar extends JoinVehicle {

    Integer doors
}

@Entity
class JoinSportsCar extends JoinCar {

    Integer topSpeed
}
