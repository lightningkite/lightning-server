package com.lightningkite.lightningdb.test

import org.junit.Test
import kotlinx.datetime.Instant
import kotlin.test.assertEquals
import com.lightningkite.lightningdb.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.random.Random

abstract class OperationsTests() {

    init { prepareModels() }
    abstract val database: Database

    @Test fun test_partials(): Unit = runBlocking {
        val collection = database.collection<LargeTestModel>("test_partials")
        var m = LargeTestModel(int = 42)
        collection.insertOne(m)
        val result = collection.findPartial(
            fields = setOf(path<LargeTestModel>().int),
            condition = Condition.Always()
        ).toList()
        assertEquals(partialOf<LargeTestModel>("int" to m.int), result.first())
    }

    @Test fun test_massUpdate(): Unit = runBlocking {
        val collection = database.collection<LargeTestModel>("test_massUpdate")
        val basis = (0..100).map { LargeTestModel(int = it) }
        collection.insert(basis)
        val cond = condition<LargeTestModel> { it.int gt 50 }
        val mod = modification<LargeTestModel> { it.boolean assign true }
        val out = collection.updateMany(cond, mod)
        assertEquals(basis.map { if(cond(it)) mod(it) else it }, collection.all().toList().sortedBy { it.int })
        assertEquals(basis.filter { cond(it) }.map { EntryChange(it, mod(it)) }.sortedBy { it.old?.int }, out.changes.sortedBy { it.old?.int })
    }

    @Test fun test_replace(): Unit = runBlocking {
        val collection = database.collection<LargeTestModel>("test_replace")
        var m = LargeTestModel()
        collection.insertOne(m)
        try {
            m = m.copy(int = 1)
            collection.replaceOneIgnoringResult(condition { it._id eq m._id }, m)
            assertEquals(m, collection.get(m._id))
        } catch(u: UnsupportedOperationException) {
            println("fine...")
        }
    }

    @Test fun test_wackyUpsert(): Unit = runBlocking {
        val collection = database.collection<LargeTestModel>("test_wackyUpsert")
        var m = LargeTestModel(int = 2, byte = 1)
        var updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.boolean assign true }, m)
        assertEquals(null, updated.old)
        assertEquals(m, updated.new)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
        updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.byte assign 1 }, LargeTestModel(byte = 1))
        assertEquals(m, updated.old)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
    }
    @Test fun test_normalUpsert(): Unit = runBlocking {
        val collection = database.collection<LargeTestModel>("test_normalUpsert")
        var m = LargeTestModel(int = 2, boolean = true)
        var updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.boolean assign true }, m)
        assertEquals(null, updated.old)
        assertEquals(m, updated.new)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
        updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.byte assign 1 }, LargeTestModel(byte = 1))
        assertEquals(m, updated.old)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
    }
    @Test fun test_modUpsert(): Unit = runBlocking {
        val collection = database.collection<LargeTestModel>("test_modUpsert")
        var m = LargeTestModel(int = 2, boolean = true, byte = 1)
        var updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.byte plusAssign 1 }, m)
        assertEquals(null, updated.old)
        assertEquals(m, updated.new)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
        updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.byte plusAssign 1 }, LargeTestModel(byte = 1))
        assertEquals(m, updated.old)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
        updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.byte plusAssign 1 }, LargeTestModel(byte = 1))
        assertEquals(m, updated.old)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
    }
    @Test fun test_wackyUpsertIgnoring(): Unit = runBlocking {
        val collection = database.collection<LargeTestModel>("test_wackyUpsertIgnoring")
        var m = LargeTestModel(int = 2)
        var updated = collection.upsertOneIgnoringResult(condition { it._id eq m._id }, modification { it.boolean assign true }, m)
        m = collection.get(m._id)!!
        assertEquals(false, updated)
        updated = collection.upsertOneIgnoringResult(condition { it._id eq m._id }, modification { it.byte assign 1 }, m)
        m = collection.get(m._id)!!
        assertEquals(true, updated)
    }
    @Test fun test_normalUpsertIgnoring(): Unit = runBlocking {
        val collection = database.collection<LargeTestModel>("test_normalUpsertIgnoring")
        var m = LargeTestModel(int = 2, boolean = true)
        var updated = collection.upsertOneIgnoringResult(condition { it._id eq m._id }, modification { it.boolean assign true }, m)
        m = collection.get(m._id)!!
        assertEquals(false, updated)
        updated = collection.upsertOneIgnoringResult(condition { it._id eq m._id }, modification { it.byte assign 1 }, m)
        m = collection.get(m._id)!!
        assertEquals(true, updated)
    }
    @Test fun test_modUpsertIgnoring(): Unit = runBlocking {
        val collection = database.collection<LargeTestModel>("test_modUpsert")
        var m = LargeTestModel(int = 2, boolean = true, byte = 1)
        var updated = collection.upsertOneIgnoringResult(condition { it._id eq m._id }, modification { it.byte plusAssign 1 }, m)
        m = collection.get(m._id)!!
        assertEquals(false, updated)
        updated = collection.upsertOneIgnoringResult(condition { it._id eq m._id }, modification { it.byte plusAssign 1 }, m)
        m = collection.get(m._id)!!
        assertEquals(true, updated)
    }

    @Test fun test_upsertOneById(): Unit = runBlocking {
        val collection = database.collection<LargeTestModel>("test_upsertOneById")
        var m = LargeTestModel(int = 2, boolean = true)
        var updated = collection.upsertOneById(m._id, m)
        assertEquals(null, updated.old)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
        updated = collection.upsertOneById(m._id, m)
        assertEquals(m, updated.old)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
    }

    @Test fun test_concurrency(): Unit = runBlocking {
        fun collection() = database.collection<LargeTestModel>("test_concurrency")
        val operations = (1..1000).map { modification<LargeTestModel> { it.int assign Random.nextInt() } }
        var m = LargeTestModel(int = 2, boolean = true)
        val opCount = AtomicInteger(0)
        val opMax = AtomicInteger(0)
        collection().insertOne(m)
        coroutineScope {
            withContext(Dispatchers.IO) {
                operations.map {
                    async {
                        val c = opCount.incrementAndGet()
                        opMax.getAndUpdate { max(it, c) }
                        collection().updateOneById(m._id, it)
                        opCount.decrementAndGet()
                    }
                }.awaitAll()
            }
        }
        // This assert won't usually work as ops are not necessarily run in order.
//        assertEquals(operations.fold(m) { acc, op -> op(acc) }, collection().get(m._id))
        assertEquals(0, opCount.get())
        println("Concurrent ops: ${opMax.get()}")
    }
}