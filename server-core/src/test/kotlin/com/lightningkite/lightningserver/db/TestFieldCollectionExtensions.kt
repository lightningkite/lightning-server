package com.lightningkite.lightningserver.db

import com.lightningkite.prepareModelsServerCore
import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import com.lightningkite.lightningserver.testmodels.TempThing
import com.lightningkite.prepareModelsShared
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
        prepareModelsShared()
        prepareModelsServerCore()
        collection = InMemoryDatabase().collection<TempThing>() as InMemoryFieldCollection
    }

    @Test
    fun testAll():Unit = runBlocking {
        collection.deleteMany(Condition.Always())

        collection.insertMany((0 until 100).toList().map { TempThing(it) })

        assertEquals(100, collection.all().count())

        var results = collection.all().toList()
        repeat(100){
            assertEquals(it, results[it]._id)
        }

        collection.insertOne(TempThing(101))
        assertEquals(101, collection.all().count())
        results = collection.all().toList()
        assertEquals(101, results[100]._id)
    }

}