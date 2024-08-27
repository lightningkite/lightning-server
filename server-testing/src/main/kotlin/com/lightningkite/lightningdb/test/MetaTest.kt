@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningdb.test

import com.lightningkite.prepareModelsServerCore
import com.lightningkite.lightningdb.*
import com.lightningkite.prepareModelsShared
import com.lightningkite.serialization.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import org.junit.Test
import java.util.*
import kotlin.test.assertContains

abstract class MetaTest {
    init {
        prepareModelsShared()
        prepareModelsServerCore()
        prepareModelsServerTesting()
    }
    abstract val database: Database

    @Test
    fun test(): Unit = runBlocking {
        val c = database.collection<MetaTestModel>()
        val toInsert = MetaTestModel(
            condition = condition { it.int gt 3 },
            modification = modification { it.int += 2 }
        )
        c.insertOne(toInsert)
        val results = c.find(Condition.Always()).toList()
        results.forEach { println(it) }
        assertContains(results, toInsert)
    }
}