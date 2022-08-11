@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningdb.test

import com.lightningkite.lightningdb.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import org.junit.Test
import java.util.*
import kotlin.test.assertContains

abstract class MetaTest {
    init { prepareModels() }
    abstract val database: Database

    @Test
    fun test(): Unit = runBlocking {
        val c = database.collection<MetaTestModel>()
        prepareModels()
        val toInsert = MetaTestModel(
            condition = condition { it.int gt 3 },
            modification = modification { it.int + 2 }
        )
        c.insertOne(toInsert)
        val results = c.find(Condition.Always()).toList()
        results.forEach { println(it) }
        assertContains(results, toInsert)
    }
}