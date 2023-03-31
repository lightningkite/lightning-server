package com.lightningkite.lightningdb.test

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import com.lightningkite.lightningdb.*

abstract class OperationsTests() {

    init { prepareModels() }
    abstract val database: Database

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
        var m = LargeTestModel(int = 2)
        var updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.boolean assign true }, m)
        assertEquals(null, updated.old)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
        updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.byte assign 1 }, m)
        assertEquals(m, updated.old)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
    }
    @Test fun test_normalUpsert(): Unit = runBlocking {
        val collection = database.collection<LargeTestModel>("test_normalUpsert")
        var m = LargeTestModel(int = 2, boolean = true)
        var updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.boolean assign true }, m)
        assertEquals(null, updated.old)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
        updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.byte assign 1 }, m)
        assertEquals(m, updated.old)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
    }
    @Test fun test_modUpsert(): Unit = runBlocking {
        val collection = database.collection<LargeTestModel>("test_modUpsert")
        var m = LargeTestModel(int = 2, boolean = true, byte = 1)
        var updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.byte plusAssign 1 }, m)
        assertEquals(null, updated.old)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
        updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.byte plusAssign 1 }, m)
        assertEquals(m, updated.old)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
        updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.byte plusAssign 1 }, m)
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
        var updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.byte plusAssign 1 }, m)
        assertEquals(null, updated.old)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
        updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.byte plusAssign 1 }, m)
        assertEquals(m, updated.old)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
        updated = collection.upsertOne(condition { it._id eq m._id }, modification { it.byte plusAssign 1 }, m)
        assertEquals(m, updated.old)
        m = collection.get(m._id)!!
        assertEquals(m, updated.new)
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
}