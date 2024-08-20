@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningdb

import com.lightningkite.*
import com.lightningkite.lightningdb.testing.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.internal.GeneratedSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.properties.Properties
import kotlinx.serialization.properties.decodeFromStringMap
import kotlinx.serialization.properties.encodeToStringMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull


class SerializationTest {
    val myJson = Json {
        serializersModule = ClientModule
    }
    val myProperties = Properties(ClientModule)

    init {
        com.lightningkite.lightningdb.prepareModels()
        com.lightningkite.lightningdb.testing.prepareModels()
    }

    @Test
    fun writeThing() {
        val out = Partial<LargeTestModel>()
        LargeTestModel.path.embeddedNullable.notNull.value1
            .setMap(LargeTestModel(embeddedNullable = ClassUsedForEmbedding()), out)
        println(out)
    }

    @Test fun time() {
        nowLocal().let {
            assertEquals(it, myJson.decodeFromString<ZonedDateTime>(myJson.encodeToString(it)))
        }
        nowLocal().toOffsetDateTime().let {
            assertEquals(it, myJson.decodeFromString<OffsetDateTime>(myJson.encodeToString(it)))
        }
    }

    @Test fun partial() {
        val serializer = PartialSerializer(User.serializer())
        val part = partialOf<User>{
            it._id assign uuid()
            it.email assign "test@test.com".trimmedCaseless()
        }
        val asText = myJson.encodeToString(serializer, part)
        println(asText)
        val restored = myJson.decodeFromString(serializer, asText)
        assertEquals(part, restored)
        println(restored)
        println(myJson.decodeFromString(serializer, """{"age": 23}"""))
    }

    @Test fun partial2() {
        val serializer = PartialSerializer(LargeTestModel.serializer())
        val part = partialOf<LargeTestModel> {
            it.embeddedNullable.notNull {
                it.value2 assign 4
            }
            it.int assign 5
            it.intNullable assign null
        }
        val asText = myJson.encodeToString(serializer, part)
        println(asText)
        val restored = myJson.decodeFromString(serializer, asText)
        assertEquals(part, restored)

        println(restored)
        println(myJson.decodeFromString(serializer, """{"embedded": { "value1": "Test" }}"""))
    }
    @Test fun partial4() {
        val serializer = PartialSerializer(LargeTestModel.serializer())
        val part = Partial(LargeTestModel(), setOf(path<LargeTestModel>().embeddedNullable.notNull.value2))
        val asText = myJson.encodeToString(serializer, part)
        println(asText)
        val restored = myJson.decodeFromString(serializer, asText)
        assertEquals(part, restored)
        println(restored)
    }
    @Test fun partial3() {
        val serializer = serializer<QueryPartial<LargeTestModel>>()
        val item = QueryPartial(
            fields = setOf(
                path<LargeTestModel>().int,
                path<LargeTestModel>().embeddedNullable.notNull.value1,
            )
        )
        val asText = myJson.encodeToString(serializer, item)
        println(asText)
        val restored = myJson.decodeFromString(serializer, asText)
        assertEquals(item, restored)
    }

    @Test fun oldSearchStyle() {
        myJson.decodeFromString<Condition<String>>("""
            { "Search": { "value": "asdf" } }
        """.trimIndent())
    }

    @Test fun executionTest() {
        val modification = modification<User> { it.age assign 22 }
        val model = User(email = "joseph@lightningkite.com".trimmedCaseless())
        assertEquals(22, modification(model).age)
    }

    @Test fun demoConditions() {
        Condition.Equal(2).cycle()
        (path<User>().email eq "Dan".trimmedCaseless()).cycle()
        (path<User>().email eq "Dan".trimmedCaseless()).cycle()
        (path<Post>().content eq "Lightning Kite").cycle()
    }
    @Test fun demoModifications() {
        Modification.Assign(2).cycle()
        modification<User> { it.email assign "Dan".trimmedCaseless() }.cycle()
        modification<Post> { it.content assign "Lightning Kite" }.cycle()
    }
    @Test fun demoSorts() {
        sort<User> {
            it.name.ascending()
            it.age.descending()
        }.cycle()
        sort<User> {
            it.name.ascending(ignoreCase = true)
            it.age.descending()
        }.cycle()
    }
    @OptIn(InternalSerializationApi::class)
    @Test fun hackTest() {
        println((Cursed.Inside.serializer(Int.serializer()) as GeneratedSerializer<*>).childSerializers().joinToString())
        println(serializer<List<Int>>().listElement())
        assertNull(serializer<Unit>().listElement())
        println(serializer<Map<String, Int>>().mapValueElement())
        println(serializer<Int?>().nullElement())
    }

    @Test fun cursedTest() {
        condition<Cursed.Inside<Int>> { it.item eq 2 }.cycle()
        condition<Cursed> { it.insideClass.item eq uuid() }.cycle()
    }

//    @Test fun metaTest() {
//        condition<MetaModel> { it.number eq 22 }.cycle()
//        condition<MetaModel> { it.condition eq condition<MetaModel> { it.number eq 22 } }.cycle()
//        modification<MetaModel> { it.number assign 22 }.cycle()
//        modification<MetaModel> { it.condition assign condition<MetaModel> { it.number eq 22 } }.cycle()
//        modification<MetaModel> { it.modification assign modification<MetaModel> { it.number assign 22 } }.cycle()
//    }

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
        Condition.FullTextSearch<LargeTestModel>("some text", true).cycle()
        (path<LargeTestModel>().instant gt now()).cycle()
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
    }

    @Test fun modifications() {
//        ((path<LargeTestModel>().int assign 2) then (path<LargeTestModel>().boolean assign true)).cycle()
        modification<LargeTestModel> {
            it.int assign 2
            it.boolean assign true
        }.cycle()
        modification<LargeTestModel> {it.intNullable.notNull += 1 }.cycle()
        modification<LargeTestModel> {it.int assign 2 }.cycle()
        modification<LargeTestModel> {it.int coerceAtMost 2 }.cycle()
        modification<LargeTestModel> {it.int coerceAtLeast 2 }.cycle()
        modification<LargeTestModel> {it.int += 2 }.cycle()
        modification<LargeTestModel> {it.int *= 2 }.cycle()
        modification<LargeTestModel> {it.string += "asdf" }.cycle()
        modification<LargeTestModel> {it.list += listOf(1, 2, 3) }.cycle()
        modification<LargeTestModel> {it.list.removeAll { it eq 2 } }.cycle()
        modification<LargeTestModel> {it.list.removeAll(listOf(1, 2)) }.cycle()
        modification<LargeTestModel> {it.list.dropFirst() }.cycle()
        modification<LargeTestModel> {it.list.dropLast() }.cycle()
        modification<LargeTestModel> {it.set.removeAll { it eq 2 } }.cycle()
        modification<LargeTestModel> {it.set.removeAll(setOf(1, 2)) }.cycle()
        modification<LargeTestModel> {it.set.dropFirst() }.cycle()
        modification<LargeTestModel> {it.set.dropLast() }.cycle()
        modification<LargeTestModel> {it.list.forEach { it += 2 } }.cycle()
        modification<LargeTestModel> {it.map += mapOf("c" to 3) }.cycle()
        modification<LargeTestModel> {it.map.modifyByKey(mapOf(
            "c" to { it += 1 }
        ))}.cycle()
        modification<LargeTestModel> { it.map.removeKeys(setOf("A")) }.cycle()
    }

    @Test fun keyPaths() {
        path<LargeTestModel>().embeddedNullable.notNull.value1.cycle()
        path<LargeTestModel>().embedded.value1.cycle()
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
    private inline fun <reified T> DataClassPathPartial<T>.cycle() {
        println("----$this----")
        val asString = myJson.encodeToString(this)
        println(asString)
        val recreated = myJson.decodeFromString<DataClassPathPartial<T>>(asString)
        println(recreated)
        assertEquals(this, recreated)
    }


    @OptIn(InternalSerializationApi::class)
    @Test fun studyCheating() {
        val a = ListSerializer(Int.serializer().nullable).nullable
        println(a)
        println(a.innerElement())
        println(a.innerElement().innerElement())
        println(a.innerElement().innerElement().innerElement())
        println(MapSerializer(String.serializer(), Int.serializer()).innerElement())
        println(MapSerializer(String.serializer(), Int.serializer()).innerElement2())

        println((Cursed.Inside.serializer(ListSerializer(Int.serializer().nullable)) as GeneratedSerializer<*>).typeParametersSerializers()[0])

        println(LargeTestModel.serializer().serializableProperties?.joinToString())

        println(Cursed.Inside.serializer(ListSerializer(Int.serializer().nullable)).serializableProperties?.joinToString { "${it.name}: ${it.serializer.descriptor.serialName}" })
    }

    @Test fun geo() {
        val geo = GeoCoordinate(41.727019, -111.8443002)
        assertEquals(geo, myJson.decodeFromString(myJson.encodeToString(geo).also { println(it) }))
        assertEquals(geo, myJson.decodeFromString(GeoCoordinateArraySerializer, myJson.encodeToString(GeoCoordinateArraySerializer, geo)))
        assertEquals(geo, myJson.decodeFromString(GeoCoordinateGeoJsonSerializer, myJson.encodeToString(GeoCoordinateGeoJsonSerializer, geo)))
    }
}