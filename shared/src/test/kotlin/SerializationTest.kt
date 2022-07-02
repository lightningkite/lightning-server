@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningdb

import kotlinx.serialization.*
import kotlinx.serialization.internal.GeneratedSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.properties.Properties
import kotlinx.serialization.properties.decodeFromStringMap
import kotlinx.serialization.properties.encodeToStringMap
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals

class SerializationTest {
    val myJson = Json {
        serializersModule = ClientModule
    }
    val myProperties = Properties(ClientModule)

    init {
        prepareModels()
    }

    @Test fun oldSearchStyle() {
        myJson.decodeFromString<Condition<String>>("""
            { "Search": { "value": "asdf" } }
        """.trimIndent())
    }

    @Test fun executionTest() {
        val modification = modification<User> { it.age assign 22 }
        val model = User(email = "joseph@lightningkite.com")
        assertEquals(22, modification(model).age)
    }

    @Test fun demoConditions() {
        Condition.Equal(2).cycle()
        (startChain<User>().email eq "Dan").cycle()
        (startChain<User>().email eq "Dan").cycle()
        (startChain<Post>().content eq "Lightning Kite").cycle()
    }
    @Test fun demoModifications() {
        Modification.Assign(2).cycle()
        (startChain<User>().email assign "Dan").cycle()
        (startChain<Post>().content assign "Lightning Kite").cycle()
    }
    @Test fun demoSorts() {
        listOf(SortPart(User::email), SortPart(User::age, ascending = false)).cycle()
    }
    @Test fun hackTest() {
        println(serializer<List<Int>>().listElement())
        println(serializer<Map<String, Int>>().mapValueElement())
        println(serializer<Int?>().nullElement())
    }

    @Test fun cursedTest() {
        condition<Cursed.Inside<Int>> { it.item eq 2 }.cycle()
        condition<Cursed> { it.insideClass.item eq UUID.randomUUID() }.cycle()
    }

    @Test fun metaTest() {
        condition<MetaModel> { it.number eq 22 }.cycle()
        condition<MetaModel> { it.condition eq condition<MetaModel> { it.number eq 22 } }.cycle()
        modification<MetaModel> { it.number assign 22 }.cycle()
        modification<MetaModel> { it.condition assign condition<MetaModel> { it.number eq 22 } }.cycle()
        modification<MetaModel> { it.modification assign modification<MetaModel> { it.number assign 22 } }.cycle()
    }

    @Test fun conditions() {
        val sampleCondition = startChain<LargeTestModel>().int eq 2
        val sampleInstance = LargeTestModel()
        Condition.Never<LargeTestModel>().cycle()
        Condition.Always<LargeTestModel>().cycle()
        Condition.And(listOf(sampleCondition)).cycle()
        Condition.Or(listOf(sampleCondition)).cycle()
        Condition.Not(sampleCondition).cycle()
        Condition.Equal(sampleInstance).cycle()
        Condition.NotEqual(sampleInstance).cycle()
        Condition.Inside(listOf(sampleInstance)).cycle()
        Condition.NotInside(listOf(sampleInstance)).cycle()
        (startChain<LargeTestModel>().int gt 2).cycle()
        (startChain<LargeTestModel>().int lt 2).cycle()
        (startChain<LargeTestModel>().int gte 2).cycle()
        (startChain<LargeTestModel>().int lte 2).cycle()
        (startChain<LargeTestModel>().string.contains("asdf", ignoreCase = true)).cycle()
        (startChain<LargeTestModel>().int.allClear(1)).cycle()
        (startChain<LargeTestModel>().int.allSet(1)).cycle()
        (startChain<LargeTestModel>().int.anyClear(1)).cycle()
        (startChain<LargeTestModel>().int.anySet(1)).cycle()
        (startChain<LargeTestModel>().list.all { it eq 2 }).cycle()
        (startChain<LargeTestModel>().list.any { it eq 2 }).cycle()
        (startChain<LargeTestModel>().list.sizesEquals(2)).cycle()
        (startChain<LargeTestModel>().map.containsKey("asdf")).cycle()
        startChain<LargeTestModel>().intNullable.notNull.gt(4).cycle()
    }

    @Test fun modifications() {
        ((startChain<LargeTestModel>().int assign 2) then (startChain<LargeTestModel>().boolean assign true)).cycle()
        (startChain<LargeTestModel>().intNullable.notNull + 1).cycle()
        (startChain<LargeTestModel>().int assign 2).cycle()
        (startChain<LargeTestModel>().int coerceAtMost 2).cycle()
        (startChain<LargeTestModel>().int coerceAtLeast 2).cycle()
        (startChain<LargeTestModel>().int + 2).cycle()
        (startChain<LargeTestModel>().int * 2).cycle()
        (startChain<LargeTestModel>().string + "asdf").cycle()
        (startChain<LargeTestModel>().list + listOf(1, 2, 3)).cycle()
        (startChain<LargeTestModel>().list.addUnique(listOf(1, 2, 3))).cycle()
        (startChain<LargeTestModel>().list.removeAll { it eq 2 }).cycle()
        (startChain<LargeTestModel>().list.removeAll(listOf(1, 2))).cycle()
        (startChain<LargeTestModel>().list.dropFirst()).cycle()
        (startChain<LargeTestModel>().list.dropLast()).cycle()
        (startChain<LargeTestModel>().list.map { it + 2 }).cycle()
        (startChain<LargeTestModel>().map + mapOf("c" to 3)).cycle()
        (startChain<LargeTestModel>().map.modifyByKey(mapOf(
            "c" to { it + 1 }
        ))).cycle()
        (startChain<LargeTestModel>().map.removeKeys(setOf("a"))).cycle()
    }

    private inline fun <reified T> Condition<T>.cycle() {
        println("----$this----")
        val asString = myJson.encodeToString(this)
        println(asString)
        val recreated = myJson.decodeFromString<Condition<T>>(asString)
        println(recreated)
        assertEquals(this, recreated)

        val asString2 = myProperties.encodeToStringMap(this)
        println(asString2)
        val recreated2 = myProperties.decodeFromStringMap<Condition<T>>(asString2)
        println(recreated2)
        assertEquals(this, recreated2)

        val inQuery = Query(condition = this)
        val asString3 = myJson.encodeToString(inQuery)
        println("Query: ${asString3}")
        val recreated3 = myJson.decodeFromString<Query<T>>(asString3)
        println("Query: ${recreated3}")
        assertEquals(inQuery, recreated3)
    }
    private inline fun <reified T> Modification<T>.cycle() {
        println("----$this----")
        val asString = myJson.encodeToString(this)
        println(asString)
        val recreated = myJson.decodeFromString<Modification<T>>(asString)
        println(recreated)
        assertEquals(this, recreated)

        val asString2 = myProperties.encodeToStringMap(this)
        println(asString2)
        val recreated2 = myProperties.decodeFromStringMap<Modification<T>>(asString2)
        println(recreated2)
        assertEquals(this, recreated2)
    }
    private inline fun <reified T> List<SortPart<T>>.cycle() {
        println("----$this----")
        val asString = myJson.encodeToString(this)
        println(asString)
        val recreated = myJson.decodeFromString<List<SortPart<T>>>(asString)
        println(recreated)
        assertEquals(this, recreated)

        val asString2 = myProperties.encodeToStringMap(this)
        println(asString2)
        val recreated2 = myProperties.decodeFromStringMap<List<SortPart<T>>>(asString2)
        println(recreated2)
        assertEquals(this, recreated2)
    }
}