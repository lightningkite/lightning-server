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
}