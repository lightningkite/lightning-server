package com.lightningkite.lightningdb.test

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.files.serverFile
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.datetime.Clock
import com.lightningkite.now
import kotlinx.serialization.encodeToString
import kotlinx.serialization.properties.encodeToStringMap
import org.junit.Test
import kotlinx.datetime.Instant
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.fail

class ConditionSimplifyKtTest {
    init {
        TestSettings
    }

    @Test fun paths() {
        condition<LargeTestModel> {
            ( it.embedded.value1 eq "sa") and (it.boolean eq false) and (it.boolean neq true) and (it.embeddedNullable.notNull.value2 eq 42)
        }.also { println(it) }.readPaths().let { println(it) }
    }

    @Test
    fun test() {
        condition<LargeTestModel> { Condition.Always<LargeTestModel>() and Condition.Never() }.simplifyOk()
        condition<LargeTestModel> { Condition.Never<LargeTestModel>() or Condition.Never() }.simplifyOk()
        condition<LargeTestModel> { it.boolean eq false }.simplifyOk()
        condition<LargeTestModel> { it.boolean.eq(false) and Condition.Always() }.simplifyOk()
        condition<LargeTestModel> { it.boolean.eq(false) and Condition.Never() }.simplifyOk()
        condition<LargeTestModel> { it.boolean.eq(false) or Condition.Always() }.simplifyOk()
        condition<LargeTestModel> { it.boolean.eq(false) or Condition.Never() }.simplifyOk()
        condition<LargeTestModel> { Condition.Always<LargeTestModel>() and it.boolean.eq(false) }.simplifyOk()
        condition<LargeTestModel> { it.boolean.eq(false) and it.byte.eq(0) }.simplifyOk()
        condition<LargeTestModel> { it.boolean.eq(false) and it.boolean.eq(true) }.simplifyOk()
        condition<LargeTestModel> { it.boolean.eq(false) and it.boolean.inside(listOf(true, false)) }.simplifyOk()
        val conditions = listOf<Condition<LargeTestModel>>(
            Condition.Always(),
            Condition.Never(),
            condition { it.int eq 2 },
            condition { it.int ne 2 },
            condition { it.int gt 2 },
            condition { it.int lt 2 },
            condition { it.int eq 0 },
            condition { it.int ne 0 },
            condition { it.int gt 0 },
            condition { it.int lt 0 },
            condition { it.int gte 2 },
            condition { it.int lte 2 },
            condition { it.int gte 0 },
            condition { it.int lte 0 },
            condition { it.int inside listOf() },
            condition { it.int inside listOf(2) },
            condition { it.int inside listOf(0, 2) },
            condition { it.int notIn listOf() },
            condition { it.int notIn listOf(2) },
            condition { it.int notIn listOf(0, 2) },
            condition { it.short eq 2 },
            condition { it.short ne 2 },
            condition { it.short gt 2 },
            condition { it.short lt 2 },
            condition { it.short eq 0 },
            condition { it.short ne 0 },
            condition { it.short gt 0 },
            condition { it.short lt 0 },
            condition { it.short gte 2 },
            condition { it.short lte 2 },
            condition { it.short gte 0 },
            condition { it.short lte 0 },
            condition { it.short inside listOf() },
            condition { it.short inside listOf(2) },
            condition { it.short inside listOf(0, 2) },
            condition { it.short notIn listOf() },
            condition { it.short notIn listOf(2) },
            condition { it.short notIn listOf(0, 2) },
        )
        var count = 0
        measureTimeMillis {
            conditions.forEach { b ->
                b.simplifyOk()
                count++
            }
            conditions.forEach { a ->
                conditions.forEach { b ->
                    (a and b).simplifyOk()
                    count++
                }
            }
            conditions.forEach { a ->
                conditions.forEach { b ->
                    (a or b).simplifyOk()
                    count++
                }
            }
            conditions.forEach { a ->
                conditions.forEach { b ->
                    conditions.forEach { c ->
                        (a and b and c).simplifyOk()
                        count++
                    }
                }
            }
            conditions.forEach { a ->
                conditions.forEach { b ->
                    conditions.forEach { c ->
                        (a or b or c).simplifyOk()
                        count++
                    }
                }
            }
            conditions.forEach { a ->
                conditions.forEach { b ->
                    conditions.forEach { c ->
                        (a and b or c).simplifyOk()
                        count++
                    }
                }
            }
            conditions.forEach { a ->
                conditions.forEach { b ->
                    conditions.forEach { c ->
                        (a or b and c).simplifyOk()
                        count++
                    }
                }
            }
        }.also {
            println("$it ms / $count; ${it.toDouble()/count} ms per item")
            if(it.toDouble()/count > 0.05) fail("Not performant enough - ${it.toDouble()/count} > 0.05 required")
        }
    }

    @Test
    fun constrainedTest() {
        condition<LargeTestModel> { (it.always and it.int.eq(2)) or it.never }.simplifyOk()
//        condition<LargeTestModel> { (it.short.notIn(setOf(0, 2)) and it.short.notIn(setOf(0, 2))) or it.short.eq(0) }.simplifyOk()
    }

    @Test
    fun serializeUrlTest() {
        Serialization.properties.encodeToStringMap(
            condition<LargeTestModel> { it.listNullable.notNull.any { it eq 429 } }
        ).also { println(it) }
    }

    val sampleData = listOf(
        LargeTestModel(),
        LargeTestModel(
            boolean = true,
            byte = 1,
            short = 1,
            int = 1,
            long = 1,
            float = 1f,
            double = 1.0,
            char = 'a',
            string = "hi",
            instant = now(),
            list = listOf(1, 2)
        ),
        LargeTestModel(
            boolean = true,
            byte = 2,
            short = 1,
            int = 2,
            long = 2,
            float = 2f,
            double = 2.0,
            char = 'b',
            string = "hi2",
            instant = now(),
            list = listOf(2, 3)
        ),
        LargeTestModel(
            boolean = true,
            byte = 2,
            short = 2,
            int = 1,
            long = 2,
            float = 2f,
            double = 2.0,
            char = 'b',
            string = "hi2",
            instant = now(),
            list = listOf(2, 3)
        ),
        LargeTestModel(
            boolean = true,
            byte = 2,
            short = 2,
            int = 2,
            long = 2,
            float = 2f,
            double = 2.0,
            char = 'b',
            string = "hi2",
            instant = now(),
            list = listOf(2, 3)
        ),
    )

    fun Condition<LargeTestModel>.simplifyOk() {
        val simplified = simplify()
//        println("Got ${Serialization.json.encodeToString(simplified)} from ${Serialization.json.encodeToString(this)}")
        for (data in sampleData)
            assertEquals(this(data), simplified(data))
    }

    @Test fun testA() {
        Serialization.csv.encodeToString(TestSettings.files().root.resolve("example").serverFile)
            .let { println(it) }
    }
}

