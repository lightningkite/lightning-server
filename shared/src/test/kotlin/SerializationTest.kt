@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.ktordb

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

    @Test fun demoConditions() {
        Condition.Equal(2).cycle()
        (User.chain.email eq "Dan").cycle()
        (Post.chain.content eq "Lightning Kite").cycle()
    }
    @Test fun demoModifications() {
        Modification.Assign(2).cycle()
        (User.chain.email assign "Dan").cycle()
        (Post.chain.content assign "Lightning Kite").cycle()
    }
    @Test fun demoSorts() {
        listOf(SortPart(UserFields.email), SortPart(UserFields.age, ascending = false)).cycle()
    }
    @Test fun hackTest() {
        println(serializer<List<Int>>().listElement())
        println(serializer<Map<String, Int>>().mapValueElement())
        println(serializer<Int?>().nullElement())
    }

    @OptIn(InternalSerializationApi::class)
    @Test fun cursedTest() {
        Cursed.Inside.serializer(serializer<Int>()).fields = InsideFields.get<Int>().fields
        condition<Cursed.Inside<Int>> { it.item eq 2 }.cycle()
        condition<Cursed> { it.insideClass.item eq UUID.randomUUID() }.cycle()
    }

    @Test fun conditions() {
        val sampleCondition = LargeTestModel.chain.int eq 2
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
        (LargeTestModel.chain.int gt 2).cycle()
        (LargeTestModel.chain.int lt 2).cycle()
        (LargeTestModel.chain.int gte 2).cycle()
        (LargeTestModel.chain.int lte 2).cycle()
        (LargeTestModel.chain.string.contains("asdf", ignoreCase = true)).cycle()
        (LargeTestModel.chain.int.allClear(1)).cycle()
        (LargeTestModel.chain.int.allSet(1)).cycle()
        (LargeTestModel.chain.int.anyClear(1)).cycle()
        (LargeTestModel.chain.int.anySet(1)).cycle()
        (LargeTestModel.chain.list.all { it eq 2 }).cycle()
        (LargeTestModel.chain.list.any { it eq 2 }).cycle()
        (LargeTestModel.chain.list.sizesEquals(2)).cycle()
        (LargeTestModel.chain.map.containsKey("asdf")).cycle()
        LargeTestModel.chain.intNullable.notNull.gt(4).cycle()
    }

    @Test fun modifications() {
        ((LargeTestModel.chain.int assign 2) then (LargeTestModel.chain.boolean assign true)).cycle()
        (LargeTestModel.chain.intNullable.notNull + 1).cycle()
        (LargeTestModel.chain.int assign 2).cycle()
        (LargeTestModel.chain.int coerceAtMost 2).cycle()
        (LargeTestModel.chain.int coerceAtLeast 2).cycle()
        (LargeTestModel.chain.int + 2).cycle()
        (LargeTestModel.chain.int * 2).cycle()
        (LargeTestModel.chain.string + "asdf").cycle()
        (LargeTestModel.chain.list + listOf(1, 2, 3)).cycle()
        (LargeTestModel.chain.list.addUnique(listOf(1, 2, 3))).cycle()
        (LargeTestModel.chain.list.removeAll { it eq 2 }).cycle()
        (LargeTestModel.chain.list.removeAll(listOf(1, 2))).cycle()
        (LargeTestModel.chain.list.dropFirst()).cycle()
        (LargeTestModel.chain.list.dropLast()).cycle()
        (LargeTestModel.chain.list.map { it + 2 }).cycle()
        (LargeTestModel.chain.map + mapOf("c" to 3)).cycle()
        (LargeTestModel.chain.map.modifyByKey(mapOf(
            "c" to { it + 1 }
        ))).cycle()
        (LargeTestModel.chain.map.removeKeys(setOf("a"))).cycle()
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