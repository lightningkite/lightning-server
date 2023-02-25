@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningdb

import kotlinx.serialization.*
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
        (path<User>().email eq "Dan").cycle()
        (path<User>().email eq "Dan").cycle()
        (path<Post>().content eq "Lightning Kite").cycle()
    }
    @Test fun demoModifications() {
        Modification.Assign(2).cycle()
        (path<User>().email assign "Dan").cycle()
        (path<Post>().content assign "Lightning Kite").cycle()
    }
    @Test fun demoSorts() {
        listOf(SortPart(User::email), SortPart(User::age, ascending = false)).cycle()
        listOf(SortPart(User::email, ignoreCase = true), SortPart(User::age, ascending = false)).cycle()
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
        val sampleCondition = path<LargeTestModel>().int eq 2
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
        (path<LargeTestModel>().int gt 2).cycle()
        (path<LargeTestModel>().int lt 2).cycle()
        (path<LargeTestModel>().int gte 2).cycle()
        (path<LargeTestModel>().int lte 2).cycle()
        (path<LargeTestModel>().string.contains("asdf", ignoreCase = true)).cycle()
        (path<LargeTestModel>().int.allClear(1)).cycle()
        (path<LargeTestModel>().int.allSet(1)).cycle()
        (path<LargeTestModel>().int.anyClear(1)).cycle()
        (path<LargeTestModel>().int.anySet(1)).cycle()
        (path<LargeTestModel>().list.all { it eq 2 }).cycle()
        (path<LargeTestModel>().list.any { it eq 2 }).cycle()
        (path<LargeTestModel>().list.sizesEquals(2)).cycle()
        (path<LargeTestModel>().set.all { it eq 2 }).cycle()
        (path<LargeTestModel>().set.any { it eq 2 }).cycle()
        (path<LargeTestModel>().set.sizesEquals(2)).cycle()
        (path<LargeTestModel>().map.containsKey("asdf")).cycle()
        path<LargeTestModel>().intNullable.toCMBuilder().notNull.gt(4).cycle()
    }

    @Test fun modifications() {
        ((path<LargeTestModel>().int assign 2) then (path<LargeTestModel>().boolean assign true)).cycle()
        (path<LargeTestModel>().intNullable.toCMBuilder().notNull + 1).cycle()
        (path<LargeTestModel>().int assign 2).cycle()
        (path<LargeTestModel>().int coerceAtMost 2).cycle()
        (path<LargeTestModel>().int coerceAtLeast 2).cycle()
        (path<LargeTestModel>().int + 2).cycle()
        (path<LargeTestModel>().int * 2).cycle()
        (path<LargeTestModel>().string + "asdf").cycle()
        (path<LargeTestModel>().list + listOf(1, 2, 3)).cycle()
        (path<LargeTestModel>().list.removeAll { it eq 2 }).cycle()
        (path<LargeTestModel>().list.removeAll(listOf(1, 2))).cycle()
        (path<LargeTestModel>().list.dropFirst()).cycle()
        (path<LargeTestModel>().list.dropLast()).cycle()
        (path<LargeTestModel>().set.removeAll { it eq 2 }).cycle()
        (path<LargeTestModel>().set.removeAll(setOf(1, 2))).cycle()
        (path<LargeTestModel>().set.dropFirst()).cycle()
        (path<LargeTestModel>().set.dropLast()).cycle()
        (path<LargeTestModel>().list.map { it + 2 }).cycle()
        (path<LargeTestModel>().map + mapOf("c" to 3)).cycle()
        (path<LargeTestModel>().map.modifyByKey(mapOf(
            "c" to { it + 1 }
        ))).cycle()
        (path<LargeTestModel>().map.removeKeys(setOf("a"))).cycle()
    }

    @Test fun keyPaths() {
        KeyPathSelf<LargeTestModel>()[LargeTestModel::embeddedNullable].safeGet(ClassUsedForEmbedding::value1).cycle()
        KeyPathSelf<LargeTestModel>()[LargeTestModel::embedded][ClassUsedForEmbedding::value1].cycle()
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
    private inline fun <reified T> KeyPathPartial<T>.cycle() {
        println("----$this----")
        val asString = myJson.encodeToString(this)
        println(asString)
        val recreated = myJson.decodeFromString<KeyPathPartial<T>>(asString)
        println(recreated)
        assertEquals(this, recreated)
    }
}