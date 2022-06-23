package com.lightningkite.ktordb

import com.lightningkite.ktordb.application.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

class AggregationsTest: MongoTest() {
    @Test
    fun test(): Unit = runBlocking {
        com.lightningkite.ktordb.application.prepareModels()
        prepareModels()

        val c = defaultMongo.collection<LargeTestModel>()
        c.insertMany(listOf(
            LargeTestModel(int = 32, byte = 0),
            LargeTestModel(int = 42, byte = 0),
            LargeTestModel(int = 52, byte = 0),
            LargeTestModel(int = 34, byte = 1),
            LargeTestModel(int = 45, byte = 1),
            LargeTestModel(int = 56, byte = 1),
        ))
        for(type in Aggregate.values()) {
            val control = c.all().toList().asSequence().map { it.int.toDouble() }.aggregate(type)
            val test: Double = c.aggregate(type, property = LargeTestModelFields.int)
            assertEquals(control, test, 0.0000001)
        }
        for(type in Aggregate.values()) {
            val control = c.all().toList().asSequence().map { it.byte to it.int.toDouble() }.aggregate(type)
            val test: Map<Byte, Double> = c.groupAggregate(type, property = LargeTestModelFields.int, groupBy = LargeTestModelFields.byte)
            assertEquals(control, test)
        }
        run {
            val control = c.all().toList().asSequence().groupingBy { it.byte }.eachCount()
            val test: Map<Byte, Int> = c.groupCount(groupBy = LargeTestModelFields.byte)
            assertEquals(control, test)
        }
        run {
            val control = c.all().toList().size
            val test = c.count()
            assertEquals(control, test)
        }
    }
}