package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.db.testmodels.TempThing
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class TestFieldCollectionExtensions {

    lateinit var collection: InMemoryFieldCollection<TempThing>

    @Before
    fun setup() {
        prepareModels()
        com.lightningkite.lightningserver.db.testmodels.prepareModels()
        collection = TestSettings.database().collection<TempThing>() as InMemoryFieldCollection
        collection.drop()
    }

    @Test
    fun testAll():Unit = runBlocking {

        collection.insertMany((0 until 100).toList().map { TempThing(it) })

        assertEquals(100, collection.all().count())

        var results = collection.all().toList()
        repeat(100){
            assertEquals(it, results[it]._id)
        }

        collection.insertOne(TempThing(1))
        assertEquals(101, collection.all().count())
        results = collection.all().toList()
        assertEquals(1, results[100]._id)
    }

}