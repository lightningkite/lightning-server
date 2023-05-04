package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.TestSettings
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimpleSignals {

    lateinit var collection: FieldCollection<TempThing>
    val thing1 = TempThing(1)
    val thing2 = TempThing(2)
    val thing3 = TempThing(3)
    var runCount = 0
    var calledIds: MutableList<Int> = mutableListOf()

    @Before
    fun setup() {
        prepareModels()
        collection = TestSettings.database().collection<TempThing>()
    }

    @Test
    fun testPostCreate(): Unit = runBlocking {

        val signaledCollection = collection.postCreate {
            calledIds.add(it._id)
            assertTrue(listOf(thing1._id, thing2._id).contains(it._id))
            runCount++
        }

        signaledCollection.insert(listOf(thing1, thing2))
        assertEquals(2, runCount)
        assertTrue(calledIds.contains(thing1._id))
        assertTrue(calledIds.contains(thing2._id))

        calledIds.clear()
        runCount = 0
        signaledCollection.updateOne(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(4)))
        assertEquals(0, runCount)
        assertTrue(calledIds.isEmpty())

        signaledCollection.replaceOne(Condition.Always(), thing3)
        assertEquals(0, runCount)
        assertTrue(calledIds.isEmpty())

        signaledCollection.deleteOne(Condition.Always())
        assertEquals(0, runCount)
        assertTrue(calledIds.isEmpty())

        signaledCollection.deleteMany(Condition.Always())
        assertEquals(0, runCount)
        assertTrue(calledIds.isEmpty())

        signaledCollection.upsertOne(
            Condition.Always(),
            Modification.OnField(TempThing::_id, Modification.Assign(4)),
            thing1
        )
        assertEquals(1, runCount)
        assertEquals(listOf(thing1._id), calledIds)

        calledIds.clear()
        runCount = 0
        signaledCollection.upsertOne(
            Condition.Always(),
            Modification.OnField(TempThing::_id, Modification.Assign(4)),
            thing1
        )
        assertEquals(0, runCount)
        assertTrue(calledIds.isEmpty())

    }


    @Test
    fun testPostDelete(): Unit = runBlocking {

        val signaledCollection = collection.postDelete {
            calledIds.add(it._id)
            assertTrue(listOf(thing1._id, thing2._id).contains(it._id))
            runCount++
        }

        signaledCollection.insert(listOf(thing1, thing2))
        assertEquals(0, runCount)
        assertTrue(calledIds.isEmpty())

        signaledCollection.updateOne(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(4)))
        assertEquals(0, runCount)
        assertTrue(calledIds.isEmpty())

        signaledCollection.replaceOne(Condition.Always(), thing3)
        assertEquals(0, runCount)
        assertTrue(calledIds.isEmpty())


        collection.deleteMany(Condition.Always())
        collection.insert(listOf(thing1, thing2))
        signaledCollection.deleteOne(Condition.Always())
        assertEquals(1, runCount)
        assertEquals(listOf(thing1._id), calledIds)


        signaledCollection.insertOne(thing1)
        calledIds.clear()
        runCount = 0
        signaledCollection.deleteMany(Condition.Always())
        assertEquals(2, runCount)
        assertEquals(listOf(thing2._id, thing1._id), calledIds)


        collection.deleteMany(Condition.Always())
        collection.insert(listOf(thing1, thing2))
        calledIds.clear()
        runCount = 0
        signaledCollection.deleteOneIgnoringOld(Condition.Always())
        assertEquals(1, runCount)
        assertEquals(listOf(thing1._id), calledIds)


        collection.deleteMany(Condition.Always())
        collection.insert(listOf(thing1, thing2))
        calledIds.clear()
        runCount = 0
        signaledCollection.deleteManyIgnoringOld(Condition.Always())
        assertEquals(2, runCount)
        assertEquals(listOf(thing1._id, thing2._id), calledIds)

    }

    @Test
    fun testPreDelete(): Unit = runBlocking {

        val signaledCollection = collection.preDelete {
            calledIds.add(it._id)
            assertTrue(listOf(thing1._id, thing2._id).contains(it._id))
            runCount++
        }

        signaledCollection.insert(listOf(thing1, thing2))
        assertEquals(0, runCount)
        assertTrue(calledIds.isEmpty())

        signaledCollection.updateOne(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(4)))
        assertEquals(0, runCount)
        assertTrue(calledIds.isEmpty())

        signaledCollection.replaceOne(Condition.Always(), thing3)
        assertEquals(0, runCount)
        assertTrue(calledIds.isEmpty())


        collection.deleteMany(Condition.Always())
        collection.insert(listOf(thing1, thing2))
        signaledCollection.deleteOne(Condition.Always())
        assertEquals(1, runCount)
        assertEquals(listOf(thing1._id), calledIds)


        signaledCollection.insertOne(thing1)
        calledIds.clear()
        runCount = 0
        signaledCollection.deleteMany(Condition.Always())
        assertEquals(2, runCount)
        assertEquals(listOf(thing2._id, thing1._id), calledIds)


        collection.deleteMany(Condition.Always())
        collection.insert(listOf(thing1, thing2))
        calledIds.clear()
        runCount = 0
        signaledCollection.deleteOneIgnoringOld(Condition.Always())
        assertEquals(1, runCount)
        assertEquals(listOf(thing1._id), calledIds)


        collection.deleteMany(Condition.Always())
        collection.insert(listOf(thing1, thing2))
        calledIds.clear()
        runCount = 0
        signaledCollection.deleteManyIgnoringOld(Condition.Always())
        assertEquals(2, runCount)
        assertEquals(listOf(thing1._id, thing2._id), calledIds)

    }

    @Test
    fun testPostChange():Unit = runBlocking {

        var signaledCollection = collection.postChange { old, new ->
            calledIds.add(new._id)
            runCount++
        }

        signaledCollection.insert(listOf(thing1, thing2))
        assertEquals(0, runCount)
        assertTrue(calledIds.isEmpty())

        signaledCollection.insert(listOf(thing1, thing2))
        signaledCollection.deleteOne(Condition.Always())
        assertEquals(0, runCount)
        assertTrue(calledIds.isEmpty())

        signaledCollection.insertOne(thing1)
        assertEquals(0, runCount)
        assertTrue(calledIds.isEmpty())

        signaledCollection.deleteMany(Condition.Always())
        assertEquals(0, runCount)
        assertTrue(calledIds.isEmpty())

        signaledCollection = collection.postChange { old, new ->
            assertEquals(thing1._id, old._id)
            assertEquals(4, new._id)
            runCount++
        }

        signaledCollection.deleteMany(Condition.Always())
        signaledCollection.insert(listOf(thing1, thing2))
        signaledCollection.replaceOne(Condition.Always(), TempThing(4))
        assertEquals(1, runCount)
        runCount = 0

        signaledCollection.deleteMany(Condition.Always())
        signaledCollection.insert(listOf(thing1, thing2))
        signaledCollection.replaceOneIgnoringResult(Condition.Always(), TempThing(4))
        assertEquals(1, runCount)
        runCount = 0



        signaledCollection.deleteMany(Condition.Always())
        signaledCollection.upsertOne(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(4)), TempThing(4))
        assertEquals(0, runCount)
        assertTrue(calledIds.isEmpty())

        signaledCollection.deleteMany(Condition.Always())
        signaledCollection.insert(listOf(thing1, thing2))
        signaledCollection.upsertOne(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(4)), TempThing(4))
        assertEquals(1, runCount)
        runCount = 0

        signaledCollection.deleteMany(Condition.Always())
        signaledCollection.insert(listOf(thing1, thing2))
        signaledCollection.upsertOneIgnoringResult(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(4)), TempThing(4))
        assertEquals(1, runCount)
        runCount = 0



        signaledCollection.deleteMany(Condition.Always())
        signaledCollection.insert(listOf(thing1, thing2))
        signaledCollection.updateOne(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(4)))
        assertEquals(1, runCount)
        runCount = 0

        signaledCollection.deleteMany(Condition.Always())
        signaledCollection.insert(listOf(thing1, thing2))
        signaledCollection.updateOneIgnoringResult(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(4)))
        assertEquals(1, runCount)
        runCount = 0


        signaledCollection = collection.postChange { old, new ->
            assertTrue { listOf(thing1._id, thing2._id).contains(old._id) }
            assertEquals(4, new._id)
            calledIds.add(old._id)
            runCount++
        }

        signaledCollection.deleteMany(Condition.Always())
        signaledCollection.insert(listOf(thing1, thing2))
        signaledCollection.updateMany(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(4)))
        assertEquals(2, runCount)
        assertEquals(listOf(1,2), calledIds)
        calledIds.clear()
        runCount = 0

        signaledCollection.deleteMany(Condition.Always())
        signaledCollection.insert(listOf(thing1, thing2))
        signaledCollection.updateManyIgnoringResult(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(4)))
        assertEquals(2, runCount)
        assertEquals(listOf(1,2), calledIds)

    }

    @Test
    fun testPostNewValue():Unit = runBlocking {

        val signaledCollection = collection.postNewValue { value ->
            calledIds.add(value._id)
            runCount++
        }

        signaledCollection.insert(listOf(thing1, thing2))
        assertEquals(listOf(thing1._id, thing2._id), calledIds)
        assertEquals(2, runCount)
        calledIds.clear()
        runCount = 0


        signaledCollection.replaceOne(Condition.Always(), thing3)
        assertEquals(listOf(thing3._id), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0

        signaledCollection.replaceOneIgnoringResult(Condition.Always(), thing1)
        assertEquals(listOf(thing1._id), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0


        collection.deleteMany(Condition.Always())
        signaledCollection.upsertOne(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(4)), thing1)
        assertEquals(listOf(thing1._id), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0

        signaledCollection.upsertOne(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(4)), thing1)
        assertEquals(listOf(4), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0

        collection.deleteMany(Condition.Always())
        signaledCollection.upsertOneIgnoringResult(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(4)), thing1)
        assertEquals(listOf(thing1._id), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0

        signaledCollection.upsertOneIgnoringResult(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(4)), thing1)
        assertEquals(listOf(4), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0


        collection.deleteMany(Condition.Always())
        collection.insert(listOf(thing1, thing2))
        signaledCollection.updateOne(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(4)))
        assertEquals(listOf(4), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0

        signaledCollection.updateOneIgnoringResult(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(5)))
        assertEquals(listOf(5), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0


        signaledCollection.updateMany(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(4)))
        assertEquals(listOf(4,4), calledIds)
        assertEquals(2, runCount)
        calledIds.clear()
        runCount = 0

        signaledCollection.updateManyIgnoringResult(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(1)))
        assertEquals(listOf(1,1), calledIds)
        assertEquals(2, runCount)
        calledIds.clear()
        runCount = 0

    }

}