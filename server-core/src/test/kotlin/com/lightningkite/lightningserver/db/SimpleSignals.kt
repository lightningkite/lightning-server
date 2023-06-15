package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.db.testmodels.TempThing
import com.lightningkite.lightningserver.db.testmodels._id
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import kotlin.math.sign
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class SimpleSignals {

    lateinit var collection: InMemoryFieldCollection<TempThing>
    val thing1 = TempThing(1)
    val thing2 = TempThing(2)
    val thing3 = TempThing(3)
    var runCount = 0
    var calledIds: MutableList<Int> = mutableListOf()

    val mod1 = Modification.OnField(TempThing::_id, Modification.Assign(4))
    val mod2 = Modification.OnField(TempThing::_id, Modification.Assign(5))

    @Before
    fun setup() {
        prepareModels()
        com.lightningkite.lightningserver.db.testmodels.prepareModels()
        collection = TestSettings.database().collection<TempThing>() as InMemoryFieldCollection
        collection.drop()
    }

    @Test
    fun testPostCreate(): Unit = runBlocking {


        var signaledCollection = collection.postCreate {
            fail("Post Create called when not suppose to")
        }

        collection.insert(listOf(thing1, thing2))
        signaledCollection.updateOne(Condition.Always(), mod1)
        signaledCollection.replaceOne(Condition.Always(), thing3)
        signaledCollection.deleteOne(Condition.Always())
        signaledCollection.deleteMany(Condition.Always())

        signaledCollection = collection.postCreate {
            calledIds.add(it._id)
            assertTrue(listOf(thing1._id, thing2._id).contains(it._id))
            runCount++
        }

        signaledCollection.insert(listOf(thing1, thing2))
        assertEquals(2, runCount)
        assertTrue(calledIds.contains(thing1._id))
        assertTrue(calledIds.contains(thing2._id))
        runCount = 0
        calledIds.clear()

        signaledCollection.deleteMany(Condition.Always())
        signaledCollection.upsertOne(Condition.Always(), mod1, thing1)
        assertEquals(1, runCount)
        assertEquals(listOf(thing1._id), calledIds)
        calledIds.clear()
        runCount = 0

        signaledCollection.upsertOne(Condition.Always(), mod1, thing1)
        assertEquals(0, runCount)
        assertTrue(calledIds.isEmpty())

    }


    @Test
    fun testPostDelete(): Unit = runBlocking {

        var signaledCollection = collection.postDelete {
            fail("Post Delete called when not suppose to")
        }

        signaledCollection.insert(listOf(thing1, thing2))
        signaledCollection.updateOne(Condition.Always(), mod1)
        signaledCollection.upsertOne(Condition.Always(), mod1, thing1)
        signaledCollection.replaceOne(Condition.Always(), thing3)

        signaledCollection = collection.postDelete {
            calledIds.add(it._id)
            assertTrue(listOf(thing1._id, thing2._id).contains(it._id))
            runCount++
        }

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
    fun testPostChange(): Unit = runBlocking {

        var signaledCollection = collection.postChange { _, _ ->
            fail("Post Change called when not suppose to")
        }

        signaledCollection.insert(listOf(thing1, thing2))
        signaledCollection.deleteOne(Condition.Always())
        signaledCollection.insertOne(thing1)
        signaledCollection.deleteMany(Condition.Always())


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
        signaledCollection.upsertOne(Condition.Always(), mod1, TempThing(4))
        assertEquals(0, runCount)
        assertTrue(calledIds.isEmpty())

        signaledCollection.deleteMany(Condition.Always())
        signaledCollection.insert(listOf(thing1, thing2))
        signaledCollection.upsertOne(Condition.Always(), mod1, TempThing(4))
        assertEquals(1, runCount)
        runCount = 0

        signaledCollection.deleteMany(Condition.Always())
        signaledCollection.insert(listOf(thing1, thing2))
        signaledCollection.upsertOneIgnoringResult(Condition.Always(), mod1, TempThing(4))
        assertEquals(1, runCount)
        runCount = 0



        signaledCollection.deleteMany(Condition.Always())
        signaledCollection.insert(listOf(thing1, thing2))
        signaledCollection.updateOne(Condition.Always(), mod1)
        assertEquals(1, runCount)
        runCount = 0

        signaledCollection.deleteMany(Condition.Always())
        signaledCollection.insert(listOf(thing1, thing2))
        signaledCollection.updateOneIgnoringResult(Condition.Always(), mod1)
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
        signaledCollection.updateMany(Condition.Always(), mod1)
        assertEquals(2, runCount)
        assertEquals(listOf(1, 2), calledIds)
        calledIds.clear()
        runCount = 0

        signaledCollection.deleteMany(Condition.Always())
        signaledCollection.insert(listOf(thing1, thing2))
        signaledCollection.updateManyIgnoringResult(Condition.Always(), mod1)
        assertEquals(2, runCount)
        assertEquals(listOf(1, 2), calledIds)

    }

    @Test
    fun testPostNewValue(): Unit = runBlocking {

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
        signaledCollection.upsertOne(Condition.Always(), mod1, thing1)
        assertEquals(listOf(thing1._id), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0

        signaledCollection.upsertOne(Condition.Always(), mod1, thing1)
        assertEquals(listOf(4), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0

        collection.deleteMany(Condition.Always())
        signaledCollection.upsertOneIgnoringResult(Condition.Always(), mod1, thing1)
        assertEquals(listOf(thing1._id), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0

        signaledCollection.upsertOneIgnoringResult(Condition.Always(), mod1, thing1)
        assertEquals(listOf(4), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0


        collection.deleteMany(Condition.Always())
        collection.insert(listOf(thing1, thing2))
        signaledCollection.updateOne(Condition.Always(), mod1)
        assertEquals(listOf(4), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0

        signaledCollection.updateOneIgnoringResult(Condition.Always(), mod2)
        assertEquals(listOf(5), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0


        signaledCollection.updateMany(Condition.Always(), mod1)
        assertEquals(listOf(4, 4), calledIds)
        assertEquals(2, runCount)
        calledIds.clear()
        runCount = 0

        signaledCollection.updateManyIgnoringResult(Condition.Always(), mod2)
        assertEquals(listOf(5, 5), calledIds)
        assertEquals(2, runCount)
        calledIds.clear()
        runCount = 0

    }

    @Test
    fun testPostRawChanges():Unit = runBlocking {

        val signaledCollection = collection.postRawChanges { changes: List<EntryChange<TempThing>> ->
            calledIds.addAll(changes.flatMap { listOf(it.old?._id, it.new?._id) }.mapNotNull { it }.toSet() )
            runCount++
        }

        signaledCollection.insert(listOf(thing1, thing2))
        assertEquals(listOf(thing1._id, thing2._id), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0


        signaledCollection.replaceOne(condition { it._id eq thing2._id }, thing3)
        assertEquals(listOf(thing2._id, thing3._id), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0


        signaledCollection.updateOne(condition { it._id eq thing3._id }, modification { it._id assign 2 })
        assertEquals(listOf(thing3._id, thing2._id), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0


        signaledCollection.upsertOne(condition { it._id eq thing2._id }, modification { it._id assign 3 }, thing3)
        assertEquals(listOf(thing2._id, thing3._id), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0


        signaledCollection.updateMany(Condition.Always(), modification { it._id assign 4 })
        assertEquals(listOf(thing1._id, 4, thing3._id), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0

        collection.drop()
        collection.insertMany(listOf(thing1, thing2, thing3))


        signaledCollection.deleteOne(Condition.Always())
        assertEquals(listOf(thing1._id), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0


        signaledCollection.deleteMany(Condition.Always())
        assertEquals(listOf(thing2._id, thing3._id), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0

        collection.drop()
        collection.insertMany(listOf(thing1, thing2, thing3))

        signaledCollection.replaceOneIgnoringResult(condition { it._id eq thing2._id }, thing3)
        assertEquals(listOf(thing2._id, thing3._id), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0


        signaledCollection.updateOneIgnoringResult(condition { it._id eq thing3._id }, modification { it._id assign 2 })
        assertEquals(listOf(thing3._id, thing2._id), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0


        signaledCollection.upsertOneIgnoringResult(condition { it._id eq thing2._id }, modification { it._id assign 3 }, thing3)
        assertEquals(listOf(thing2._id, thing3._id), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0


        signaledCollection.updateManyIgnoringResult(Condition.Always(), modification { it._id assign 4 })
        assertEquals(listOf(thing1._id, 4, thing3._id), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0


        collection.drop()
        collection.insertMany(listOf(thing1, thing2, thing3))


        signaledCollection.deleteOneIgnoringOld(Condition.Always())
        assertEquals(listOf(thing1._id), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0


        signaledCollection.deleteManyIgnoringOld(Condition.Always())
        assertEquals(listOf(thing2._id, thing3._id), calledIds)
        assertEquals(1, runCount)
        calledIds.clear()
        runCount = 0

    }


    @Test
    fun testInterceptCreate(): Unit = runBlocking {

        var signaledCollection = collection.interceptCreate {
            fail("Intercept Create called when not suppose to")
        }

        collection.insert(listOf(thing1, thing2))
        signaledCollection.updateOne(Condition.Always(), mod1)
        signaledCollection.replaceOne(Condition.Always(), thing3)
        signaledCollection.deleteOne(Condition.Always())
        signaledCollection.deleteMany(Condition.Always())


        signaledCollection = collection.interceptCreate {
            calledIds.add(it._id)
            assertTrue(listOf(thing1._id, thing2._id).contains(it._id))
            runCount++
            it
        }

        signaledCollection.insert(listOf(thing1, thing2))
        assertEquals(2, runCount)
        assertTrue(calledIds.contains(thing1._id))
        assertTrue(calledIds.contains(thing2._id))
        calledIds.clear()
        runCount = 0

        signaledCollection.upsertOne(Condition.Always(), mod1, thing1)
        assertEquals(1, runCount)
        assertEquals(listOf(thing1._id), calledIds)
        calledIds.clear()
        runCount = 0

        signaledCollection.upsertOne(Condition.Always(), mod1, thing1)
        assertEquals(1, runCount)
        assertEquals(listOf(thing1._id), calledIds)

    }


    @Test
    fun testInterceptDelete(): Unit = runBlocking {

        var signaledCollection = collection.interceptDelete {
            fail("Intercept Delete called when not suppose to")
        }

        signaledCollection.insert(listOf(thing1, thing2))
        signaledCollection.updateOne(Condition.Always(), mod1)
        signaledCollection.updateMany(Condition.Always(), mod1)
        signaledCollection.replaceOne(Condition.Always(), thing3)
        signaledCollection.upsertOne(Condition.Always(), mod1, thing3)


        signaledCollection = collection.interceptDelete {
            calledIds.add(it._id)
            assertTrue(listOf(thing1._id, thing2._id).contains(it._id))
            runCount++
        }

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
    fun testInterceptReplace(): Unit = runBlocking {

        var signaledCollection = collection.interceptReplace {
            fail("Intercept Replace called when not suppose to")
        }

        signaledCollection.insert(listOf(thing1, thing2))
        signaledCollection.updateOne(Condition.Always(), mod1)
        signaledCollection.updateMany(Condition.Always(), mod1)
        signaledCollection.deleteOne(Condition.Always())
        signaledCollection.deleteMany(Condition.Always())
        signaledCollection.upsertOne(Condition.Always(), mod1, thing1)
        signaledCollection.upsertOneIgnoringResult(Condition.Always(), mod1, thing1)


        signaledCollection = collection.interceptReplace { value ->
            calledIds.add(value._id)
            runCount++
            value
        }

        signaledCollection.deleteMany(Condition.Always())
        signaledCollection.insert(listOf(thing1, thing2))
        signaledCollection.replaceOne(Condition.Always(), TempThing(4))
        assertEquals(1, runCount)
        assertEquals(listOf(4), calledIds)
        runCount = 0
        calledIds.clear()

        signaledCollection.deleteMany(Condition.Always())
        signaledCollection.insert(listOf(thing1, thing2))
        signaledCollection.replaceOneIgnoringResult(Condition.Always(), TempThing(5))
        assertEquals(1, runCount)
        assertEquals(listOf(5), calledIds)
        runCount = 0
        calledIds.clear()

        signaledCollection.deleteMany(Condition.Always())
        signaledCollection.insert(listOf(thing1, thing2))
        signaledCollection.upsertOne(Condition.Always(), Modification.Assign(TempThing(4)), TempThing(4))
        assertEquals(1, runCount)
        assertEquals(listOf(4), calledIds)
        runCount = 0
        calledIds.clear()

        signaledCollection.deleteMany(Condition.Always())
        signaledCollection.insert(listOf(thing1, thing2))
        signaledCollection.upsertOneIgnoringResult(Condition.Always(), Modification.Assign(TempThing(4)), TempThing(4))
        assertEquals(1, runCount)
        assertEquals(listOf(4), calledIds)
        runCount = 0
        calledIds.clear()

    }


    @Test
    fun testInterceptModification(): Unit = runBlocking {

        var signaledCollection = collection.interceptModification {
            fail("Intercept Modification called when not suppose to")
        }

        signaledCollection.insert(listOf(thing1, thing2))
        signaledCollection.replaceOne(Condition.Always(), TempThing(4))
        signaledCollection.replaceOneIgnoringResult(Condition.Always(), TempThing(5))
        signaledCollection.deleteOne(Condition.Always())
        signaledCollection.deleteMany(Condition.Always())


        signaledCollection = collection.interceptModification { value ->
            runCount++
            value
        }

        signaledCollection.updateOne(Condition.Always(), mod1)
        assertEquals(1, runCount)
        runCount = 0

        signaledCollection.updateMany(Condition.Always(), mod1)
        assertEquals(1, runCount)
        runCount = 0

        signaledCollection.upsertOne(Condition.Always(), mod1, thing1)
        assertEquals(1, runCount)
        runCount = 0

        signaledCollection.upsertOne(Condition.Always(), mod1, thing1)
        assertEquals(1, runCount)
        runCount = 0

    }


    @Test
    fun testInterceptChange(): Unit = runBlocking {

        var signaledCollection = collection.interceptChange { value ->
            fail("Intercept Change called when not suppose to")
        }

        collection.insert(listOf(thing1, thing2))
        signaledCollection.deleteOne(Condition.Always())
        signaledCollection.deleteMany(Condition.Always())

        signaledCollection = collection.interceptChange { value ->
            if (value is Modification.Assign) {
                calledIds.add(value.value._id)
            } else {
                assertEquals(mod1, value)
            }
            runCount++
            value
        }

        signaledCollection.insert(listOf(thing1, thing2))
        assertEquals(2, runCount)
        assertEquals(listOf(thing1._id, thing2._id), calledIds)
        runCount = 0
        calledIds.clear()


        signaledCollection.replaceOne(Condition.Always(), thing3)
        assertEquals(1, runCount)
        assertEquals(listOf(thing3._id), calledIds)
        runCount = 0
        calledIds.clear()

        signaledCollection.replaceOneIgnoringResult(Condition.Always(), thing1)
        assertEquals(1, runCount)
        assertEquals(listOf(thing1._id), calledIds)
        runCount = 0
        calledIds.clear()


        collection.deleteMany(Condition.Always())
        signaledCollection.upsertOne(Condition.Always(), mod1, thing1)
        assertEquals(2, runCount)
        assertEquals(listOf(thing1._id), calledIds)
        runCount = 0
        calledIds.clear()

        signaledCollection.upsertOne(Condition.Always(), mod1, thing1)
        assertEquals(2, runCount)
        assertEquals(listOf(thing1._id), calledIds)
        runCount = 0
        calledIds.clear()


        collection.deleteMany(Condition.Always())
        signaledCollection.upsertOne(Condition.Always(), Modification.Assign(thing2), thing1)
        assertEquals(2, runCount)
        assertEquals(listOf(thing2._id, thing1._id), calledIds)
        runCount = 0
        calledIds.clear()

        signaledCollection.upsertOne(Condition.Always(), Modification.Assign(thing2), thing1)
        assertEquals(2, runCount)
        assertEquals(listOf(thing2._id, thing1._id), calledIds)
        runCount = 0
        calledIds.clear()

        collection.deleteMany(Condition.Always())
        signaledCollection.upsertOneIgnoringResult(Condition.Always(), mod1, thing1)
        assertEquals(2, runCount)
        assertEquals(listOf(thing1._id), calledIds)
        runCount = 0
        calledIds.clear()

        signaledCollection.upsertOneIgnoringResult(Condition.Always(), mod1, thing1)
        assertEquals(2, runCount)
        assertEquals(listOf(thing1._id), calledIds)
        runCount = 0
        calledIds.clear()

        collection.deleteMany(Condition.Always())
        signaledCollection.upsertOneIgnoringResult(Condition.Always(), Modification.Assign(thing2), thing1)
        assertEquals(2, runCount)
        assertEquals(listOf(thing2._id, thing1._id), calledIds)
        runCount = 0
        calledIds.clear()

        signaledCollection.upsertOneIgnoringResult(Condition.Always(), Modification.Assign(thing2), thing1)
        assertEquals(2, runCount)
        assertEquals(listOf(thing2._id, thing1._id), calledIds)
        runCount = 0
        calledIds.clear()


        collection.deleteMany(Condition.Always())
        collection.insert(listOf(thing1, thing2))
        signaledCollection.updateOne(Condition.Always(), mod1)
        assertEquals(1, runCount)
        runCount = 0

        signaledCollection.updateOneIgnoringResult(Condition.Always(), mod1)
        assertEquals(1, runCount)
        runCount = 0

        signaledCollection.updateMany(Condition.Always(), mod1)
        assertEquals(1, runCount)
        runCount = 0

        signaledCollection.updateManyIgnoringResult(Condition.Always(), mod1)
        assertEquals(1, runCount)
        runCount = 0

    }

    @Test
    fun testInterceptChangePerInstance(): Unit = runBlocking {

        var signaledCollection = collection.interceptChangePerInstance { value, mod ->
            fail("Intercept Change Per Instance called when not suppose to")
        }

        collection.insert(listOf(thing1, thing2))
        signaledCollection.deleteOne(Condition.Always())
        signaledCollection.deleteMany(Condition.Always())

        signaledCollection = collection.interceptChangePerInstance { value, mod ->
            calledIds.add(value._id)
            runCount++
            mod
        }

        signaledCollection.insert(listOf(thing1, thing2))
        assertEquals(listOf(thing1._id, thing2._id), calledIds)
        assertEquals(2, runCount)
        calledIds.clear()
        runCount = 0

        //TODO: Finish this Unit Test. I am unsure how this is suppose to work which is why I left it.
//        signaledCollection = collection.interceptChangePerInstance { value, mod ->
//            if(mod is Modification.Assign){
//                calledIds.add(mod.value._id)
//            }
//            calledIds.add(value._id)
//            runCount++
//            mod
//        }
//
//        signaledCollection.replaceOne(Condition.Always(), thing3)
//        assertEquals(listOf(thing3._id, thing1._id), calledIds)
//        assertEquals(1, runCount)
//        calledIds.clear()
//        runCount = 0

    }

}
