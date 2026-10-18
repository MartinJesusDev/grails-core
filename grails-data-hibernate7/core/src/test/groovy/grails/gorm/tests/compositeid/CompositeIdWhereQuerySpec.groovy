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

package grails.gorm.tests.compositeid

import grails.gorm.transactions.Rollback
import org.grails.orm.hibernate.HibernateDatastore
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * The where query syntax and the bulk writes next to an entity whose identifier is composite, a
 * mapping the JPA criteria tests already cover from the other APIs.
 */
@Rollback
class CompositeIdWhereQuerySpec extends Specification {

    @Shared
    @AutoCleanup
    HibernateDatastore datastore = new HibernateDatastore(CompositeIdSimple, CompositeIdToMany, Author, Book)

    void "the where syntax queries a composite id entity"() {
        given:
        new CompositeIdSimple(name: 'a', age: 1L).save(failOnError: true, flush: true)
        new CompositeIdSimple(name: 'b', age: 2L).save(failOnError: true, flush: true)

        when:
        def byName = CompositeIdSimple.where { name == 'a' }.list()*.name
        def byAge = CompositeIdSimple.where { age > 1L }.list()*.name
        def count = CompositeIdSimple.where {}.count()
        def aboveAverage = CompositeIdSimple.where { age > avg(age) }.list()*.name

        then:
        byName == ['a']
        byAge == ['b']
        count == 2
        aboveAverage == ['b']
    }

    void "the where syntax queries the associations of a composite id entity"() {
        given:
        def savedAuthor = new Author(name: 'Author').save()
        def savedBook = new Book(title: 'Book').save()
        def join = new CompositeIdToMany(author: savedAuthor, book: savedBook).save(failOnError: true, flush: true)
        Long authorId = savedAuthor.id
        Long bookId = savedBook.id

        when:
        def byAuthor = CompositeIdToMany.where { author.id == authorId }.list()
        def byBook = CompositeIdToMany.where { book.id == bookId }.list()

        then:
        byAuthor == [join]
        byBook == [join]
    }

    void "a bulk write selects the rows of a composite id entity"() {
        given:
        new CompositeIdSimple(name: 'a', age: 1L).save(failOnError: true, flush: true)
        new CompositeIdSimple(name: 'b', age: 2L).save(failOnError: true, flush: true)

        when:
        Number updated = CompositeIdSimple.where { name == 'a' }.updateAll([age: 9L])
        datastore.sessionFactory.currentSession.clear()
        def updatedRows = CompositeIdSimple.where { age == 9L }.list()*.name

        then:
        updated == 1
        updatedRows == ['a']

        when:
        Number deleted = CompositeIdSimple.where { age == 2L }.deleteAll()
        datastore.sessionFactory.currentSession.clear()
        def remaining = CompositeIdSimple.where {}.count()

        then:
        deleted == 1
        remaining == 1
    }
}
