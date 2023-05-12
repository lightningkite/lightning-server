package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.db.testmodels.TempThing
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestAbstractSignalFieldCollection {

    lateinit var collection: InMemoryFieldCollection<TempThing>
    val thing1 = TempThing(1)
    val thing2 = TempThing(2)
    val thing3 = TempThing(3)
    var signalCalled = false

    @Before
    fun setup() {
        prepareModels()
        com.lightningkite.lightningserver.db.testmodels.prepareModels()
        collection = TestSettings.database().collection<TempThing>() as InMemoryFieldCollection
        collection.drop()
        collection.signals.clear()
    }

    @Test
    fun testInsert(): Unit = runBlocking {
        collection.registerRawSignal {
            assertEquals(1, it.changes.size)
            assertEquals(EntryChange(null, thing1), it.changes.first())
            signalCalled = true
        }

        signalCalled = false
        collection.insert(listOf(thing1))
        assertTrue(signalCalled)


        collection.signals.clear()
        collection.registerRawSignal {
            assertEquals(listOf(EntryChange(null, thing1), EntryChange(null, thing2)), it.changes)
            signalCalled = true
        }

        signalCalled = false
        collection.insert(listOf(thing1, thing2))
        assertTrue(signalCalled)

    }

    @Test
    fun testDelete(): Unit = runBlocking {


        collection.insert(listOf(thing1, thing2))
        collection.registerRawSignal {
            assertEquals(1, it.changes.size)
            assertEquals(EntryChange(thing1, null), it.changes.first())
            signalCalled = true
        }
        signalCalled = false
        collection.deleteOne(Condition.Always())
        assertTrue(signalCalled)
        signalCalled = false
        collection.signals.clear()


        collection.registerRawSignal {
            assertEquals(1, it.changes.size)
            assertEquals(EntryChange(thing2, null), it.changes.first())
            signalCalled = true
        }
        signalCalled = false
        collection.deleteOne(Condition.Always())
        assertTrue(signalCalled)
        signalCalled = false
        collection.signals.clear()


        collection.drop()
        collection.insert(listOf(thing1, thing2))
        collection.registerRawSignal {
            assertEquals(listOf(EntryChange(thing1, null), EntryChange(thing2, null)), it.changes)
            signalCalled = true
        }
        collection.deleteMany(Condition.Always())
        assertTrue(signalCalled)
        signalCalled = false

        collection.signals.clear()
        collection.registerRawSignal {
            assertTrue { it.changes.isEmpty() }
            signalCalled = true
        }
        collection.deleteMany(Condition.Always())
        assertTrue(signalCalled)
        collection.signals.clear()


        collection.insert(listOf(thing1, thing2))
        collection.registerRawSignal {
            assertEquals(1, it.changes.size)
            assertEquals(EntryChange(thing1, null), it.changes.first())
            signalCalled = true
        }
        signalCalled = false
        collection.deleteOneIgnoringOld(Condition.Always())
        assertTrue(signalCalled)
        signalCalled = false
        collection.signals.clear()


        collection.insert(listOf(thing1))
        collection.registerRawSignal {
            assertEquals(listOf(EntryChange(thing2, null), EntryChange(thing1, null)), it.changes)
            signalCalled = true
        }
        collection.deleteManyIgnoringOld(Condition.Always())
        assertTrue(signalCalled)
        signalCalled = false
        collection.signals.clear()


        collection.insert(listOf(thing1, thing2))
        collection.registerRawSignal {
            signalCalled = true
        }
        signalCalled = false
        collection.deleteOne(Condition.Never())
        assertFalse(signalCalled)

    }

    @Test
    fun testReplace(): Unit = runBlocking {

        collection.insert(listOf(thing1, thing2))
        collection.registerRawSignal {
            signalCalled = true
        }
        signalCalled = false
        collection.replaceOne(Condition.Never(), thing3)
        assertFalse(signalCalled)


        collection.registerRawSignal {
            assertEquals(1, it.changes.size)
            assertEquals(EntryChange(thing1, thing3), it.changes.first())
            signalCalled = true
        }
        signalCalled = false
        collection.replaceOne(Condition.Always(), thing3)
        assertTrue(signalCalled)
        signalCalled = false
        collection.signals.clear()


        collection.registerRawSignal {
            assertEquals(1, it.changes.size)
            assertEquals(EntryChange(thing3, thing1), it.changes.first())
            signalCalled = true
        }
        signalCalled = false
        collection.replaceOneIgnoringResult(Condition.Always(), thing1)
        assertTrue(signalCalled)
        collection.signals.clear()

    }

    @Test
    fun testUpdate(): Unit = runBlocking {


        collection.drop()
        collection.insert(listOf(thing1, thing2))
        collection.registerRawSignal {
            assertEquals(1, it.changes.size)
            val change = it.changes.first()
            assertEquals(1, change.old?._id)
            assertEquals(3, change.new?._id)
            signalCalled = true
        }
        signalCalled = false
        collection.updateOne(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(3)))
        assertTrue(signalCalled)
        signalCalled = false
        collection.signals.clear()


        collection.drop()
        collection.insert(listOf(thing1, thing2))
        collection.registerRawSignal {
            assertEquals(2, it.changes.size)
            assertEquals(listOf(EntryChange(thing1, thing3), EntryChange(thing2, thing3)), it.changes)
            signalCalled = true
        }
        signalCalled = false
        collection.updateMany(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(3)))
        assertTrue(signalCalled)
        signalCalled = false
        collection.signals.clear()


        collection.drop()
        collection.insert(listOf(thing1, thing2))
        collection.registerRawSignal {
            assertEquals(1, it.changes.size)
            val change = it.changes.first()
            assertEquals(1, change.old?._id)
            assertEquals(3, change.new?._id)
            signalCalled = true
        }
        signalCalled = false
        collection.updateOneIgnoringResult(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(3)))
        assertTrue(signalCalled)
        signalCalled = false
        collection.signals.clear()


        collection.drop()
        collection.insert(listOf(thing1, thing2))
        collection.registerRawSignal {
            assertEquals(2, it.changes.size)
            assertEquals(listOf(EntryChange(thing1, thing3), EntryChange(thing2, thing3)), it.changes)
            signalCalled = true
        }
        signalCalled = false
        collection.updateManyIgnoringResult(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(3)))
        assertTrue(signalCalled)
        signalCalled = false
        collection.signals.clear()


    }

    @Test
    fun testUpsert(): Unit = runBlocking {


        collection.drop()
        collection.registerRawSignal {
            assertEquals(1, it.changes.size)
            val change = it.changes.first()
            assertEquals(thing1, change.new)
            signalCalled = true
        }
        signalCalled = false
        collection.upsertOne(Condition.Always(), modification { }, thing1)
        assertTrue(signalCalled)
        signalCalled = false
        collection.signals.clear()

        collection.drop()
        collection.insert(listOf(thing1))
        collection.registerRawSignal {
            assertEquals(1, it.changes.size)
            val change = it.changes.first()
            assertEquals(1, change.old?._id)
            assertEquals(4, change.new?._id)
            signalCalled = true
        }
        signalCalled = false
        collection.upsertOne(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(4)), thing1)
        assertTrue(signalCalled)
        signalCalled = false
        collection.signals.clear()

        collection.drop()
        collection.registerRawSignal {
            assertEquals(1, it.changes.size)
            val change = it.changes.first()
            assertEquals(thing1, change.new)
            signalCalled = true
        }
        signalCalled = false
        collection.upsertOneIgnoringResult(Condition.Always(), modification { }, thing1)
        assertTrue(signalCalled)
        signalCalled = false
        collection.signals.clear()

        collection.drop()
        collection.insert(listOf(thing1))
        collection.registerRawSignal {
            assertEquals(1, it.changes.size)
            val change = it.changes.first()
            assertEquals(1, change.old?._id)
            assertEquals(4, change.new?._id)
            signalCalled = true
        }
        signalCalled = false
        collection.upsertOneIgnoringResult(Condition.Always(), Modification.OnField(TempThing::_id, Modification.Assign(4)), thing1)
        assertTrue(signalCalled)
        signalCalled = false
        collection.signals.clear()

    }

}