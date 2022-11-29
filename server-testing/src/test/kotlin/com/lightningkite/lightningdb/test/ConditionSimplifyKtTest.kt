package com.lightningkite.lightningdb.test

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.encodeToString
import kotlinx.serialization.properties.encodeToStringMap
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals

class ConditionSimplifyKtTest {
    init {
        TestSettings
    }
    @Test
    fun test() {
        condition<LargeTestModel> { it.boolean eq false }.simplifyOk()
        condition<LargeTestModel> { it.boolean.eq(false) and it.byte.eq(0) }.simplifyOk()
        condition<LargeTestModel> { it.boolean.eq(false) and it.boolean.eq(true) }.simplifyOk()
        condition<LargeTestModel> { it.boolean.eq(false) and it.boolean.inside(listOf(true, false)) }.simplifyOk()
        val conditions = listOf<Condition<LargeTestModel>>(
            Condition.Always(),
            Condition.Never(),
            condition { it.int eq 1 },
            condition { it.int gt 1 },
            condition { it.int lt 1 },
            condition { it.int gt 0 },
            condition { it.int lt 0 },
            condition { it.int gte 1 },
            condition { it.int lte 1 },
            condition { it.int gte 0 },
            condition { it.int lte 0 },
            condition { it.int inside listOf(1) },
            condition { it.int inside listOf(0, 1) },
            condition { it.int notIn listOf(1) },
            condition { it.int notIn listOf(0, 1) },
        )
        conditions.forEach { a ->
            conditions.forEach { b ->
                (a and b).simplifyOk()
            }
        }
    }

    @Test fun serializeUrlTest() {
        Serialization.properties.encodeToStringMap(
        condition<LargeTestModel> { it.listNullable.notNull.any { it eq 429 } }
        ).also { println(it) }
    }

    val sampleData = listOf(
        LargeTestModel(),
        LargeTestModel(boolean = true, byte = 1, short = 1, int = 1, long = 1, float = 1f, double = 1.0, char = 'a', string = "hi", instant = Instant.now(), list = listOf(1, 2),)
    )

    fun Condition<LargeTestModel>.simplifyOk() {
        println("Got ${Serialization.json.encodeToString(simplify())} from ${Serialization.json.encodeToString(this)}")
        for(data in sampleData)
            assertEquals(this(data), simplify()(data))
    }
}