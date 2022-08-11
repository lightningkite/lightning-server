package com.lightningkite.lightningdb.test

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import com.lightningkite.lightningdb.*

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
            val test: Double? = c.aggregate(type, property = LargeTestModel::int)
            if(control == null || test == null) fail()
            assertEquals(control, test, 0.0000001)
        }
        for(type in Aggregate.values()) {
            val control = c.all().toList().asSequence().map { it.byte to it.int.toDouble() }.aggregate(type)
            val test: Map<Byte, Double?> = c.groupAggregate(type, property = LargeTestModel::int, groupBy = LargeTestModel::byte)
            assertEquals(control, test)
        }
        for(type in Aggregate.values()) {
            val control = c.all().toList().asSequence().map { it.int.toDouble() }.filter { false }.aggregate(type)
            val test: Double? = c.aggregate(type, property = LargeTestModel::int, condition = Condition.Never())
            assertEquals(control, test)
        }
        for(type in Aggregate.values()) {
            val control = c.all().toList().asSequence().map { it.byte to it.int.toDouble() }.filter { false }.aggregate(type)
            val test: Map<Byte, Double?> = c.groupAggregate(type, property = LargeTestModel::int, groupBy = LargeTestModel::byte, condition = Condition.Never())
            assertEquals(control, test)
        }
        run {
            val control = c.all().toList().asSequence().groupingBy { it.byte }.eachCount()
            val test: Map<Byte, Int> = c.groupCount(groupBy = LargeTestModel::byte)
            assertEquals(control, test)
        }
        run {
            val control = c.all().toList().size
            val test = c.count()
            assertEquals(control, test)
        }
    }
}