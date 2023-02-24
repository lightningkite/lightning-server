package com.lightningkite.lightningdb.test

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import com.lightningkite.lightningdb.*
import kotlin.test.assertNull

abstract class AggregationsTest() {

    init { prepareModels() }
    abstract val database: Database

    @Test
    fun test(): Unit = runBlocking {

        val c = database.collection<LargeTestModel>()
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
            val test: Double? = c.aggregate(type, property = startChain<LargeTestModel>().int)
            if(control == null || test == null) fail()
            assertEquals(control, test, 0.0000001)
        }
        for(type in Aggregate.values()) {
            val control = c.all().toList().asSequence().map { it.byte to it.int.toDouble() }.aggregate(type)
            val test: Map<Byte, Double?> = c.groupAggregate(type, property = startChain<LargeTestModel>().int, groupBy = startChain<LargeTestModel>().byte)
            assertEquals(control.keys, test.keys)
            for(key in control.keys) {
                assertEquals(control[key]!!, test[key]!!, 0.0000001)
            }
        }
        for(type in Aggregate.values()) {
            val control = c.all().toList().asSequence().map { it.int.toDouble() }.filter { false }.aggregate(type)
            val test: Double? = c.aggregate(type, property = startChain<LargeTestModel>().int, condition = Condition.Never())
            if(control == null) assertNull(test)
            else assertEquals(control, test!!, 0.0000001)
        }
        for(type in Aggregate.values()) {
            val control = c.all().toList().asSequence().map { it.byte to it.int.toDouble() }.filter { false }.aggregate(type)
            val test: Map<Byte, Double?> = c.groupAggregate(type, property = startChain<LargeTestModel>().int, groupBy = startChain<LargeTestModel>().byte, condition = Condition.Never())
            assertEquals(control.keys, test.keys)
            for(key in control.keys) {
                assertEquals(control[key]!!, test[key]!!, 0.0000001)
            }
        }
        run {
            val control = c.all().toList().asSequence().groupingBy { it.byte }.eachCount()
            val test: Map<Byte, Int> = c.groupCount(groupBy = startChain<LargeTestModel>().byte)
            assertEquals(control, test)
        }
        run {
            val control = c.all().toList().size
            val test = c.count()
            assertEquals(control, test)
        }
    }
}