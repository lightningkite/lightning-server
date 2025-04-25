@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningserver.db

import com.lightningkite.*
import com.lightningkite.lightningserver.db.testing.*
import com.lightningkite.lightningserver.monitoring.FunnelSummary
import com.lightningkite.serialization.ClientModule
import com.lightningkite.serialization.*
import com.lightningkite.serialization.partialOf
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
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull


class SerializationTest {
    val myJson = Json {
        serializersModule = ClientModule
        encodeDefaults = true
    }
    val myProperties = Properties(ClientModule)
    val myProtobuf = ProtoBuf { this.encodeDefaults = true }

    init {
        prepareModelsShared()
        prepareModelsSharedTest()
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
            it._id assign UUID.random()
            it.email assign "test@test.com".trimmedCaseless()
        }
        val asText = myJson.encodeToString(serializer, part)
        println(asText)
        val restored = myJson.decodeFromString(serializer, asText)
        assertEquals(part, restored)
        println(restored)
        println(myJson.decodeFromString(serializer, """{"age": 23}"""))
    }

    @Test fun partialFunnelSummary() {
        val serializer = PartialSerializer(FunnelSummary.serializer())
        serializer.descriptor
    }

    @Test fun partial2() {
        val serializer = PartialSerializer(LargeTestModel.serializer())
        val part = partialOf<LargeTestModel> {
            it.embeddedNullable.notNull.value2 assign 4
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

    @Suppress("Deprecation")
    @Test fun partial4() {
        val serializer = PartialSerializer(LargeTestModel.serializer())
        val part = Partial(LargeTestModel(), setOf(path<LargeTestModel>().embeddedNullable.notNull.value2))
        val asText = myJson.encodeToString(serializer, part)
        println(asText)
        val restored = myJson.decodeFromString(serializer, asText)
        assertEquals(part, restored)
        println(restored)
    }

    @Suppress("Deprecation")
    @Test fun partial6() {
        val serializer = PartialSerializer(LargeTestModel.serializer())
        val part = Partial(LargeTestModel(embeddedNullable = ClassUsedForEmbedding()), setOf(path<LargeTestModel>().embeddedNullable))
        val asText = myJson.encodeToString(serializer, part)
        println(asText)
        val restored = myJson.decodeFromString(serializer, asText)
        assertEquals(part, restored)
        println(restored)
    }

    @Suppress("Deprecation")
    @Test fun partial7() {
        val serializer = PartialSerializer(LargeTestModel.serializer())
        val part = Partial(LargeTestModel(), setOf(path<LargeTestModel>().embeddedNullable))
        val asText = myJson.encodeToString(serializer, part)
        println(asText)
        val restored = myJson.decodeFromString(serializer, asText)
        assertEquals(part, restored)
        println(restored)
    }

    @Suppress("Deprecation")
    @Test fun partial5() {
        val serializer = PartialSerializer(LargeTestModel.serializer())
        val part = Partial(LargeTestModel(), setOf(path<LargeTestModel>().embedded))
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
    @Test fun partialMods() {
        val p = partialOf<LargeTestModel> {
            it.int assign 3
            it.embedded assign partialOf<ClassUsedForEmbedding> {
                it.value2 assign 4
            }
            it.embeddedNullable assign ClassUsedForEmbedding("test")
        }
        println(p.toModification())
    }
    @Test fun dataClassPathForgotQuestionMark() {
        println(DataClassPathSerializer(LargeTestModel.serializer()).fromString("embeddedNullable.value1"))
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
        condition { it.email.contains("@lightningkite.com") and it.name.eq("Dan") }
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
        condition<Cursed> { it.insideClass.item eq UUID.random() }.cycle()
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
        (Condition.Never as Condition<LargeTestModel>).cycle()
        (Condition.Always as Condition<LargeTestModel>).cycle()
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
        (path<LargeTestModel>().trimmedString.contains("asdf", ignoreCase = true)).cycle()
        (path<LargeTestModel>().caselessString.contains("asdf", ignoreCase = true)).cycle()
        (path<LargeTestModel>().trimmedCaselessString.contains("asdf", ignoreCase = true)).cycle()
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

    @Test fun samples() {
        condition<LargeTestModel> { it.list.any { it.gt(8) or it.lt(2) } }.let {
            println(it)
            println(myJson.encodeToString(it))
        }

    }

    @Test fun modifications() {
//        ((path<LargeTestModel>().int assign 2) then (path<LargeTestModel>().boolean assign true)).cycle()
        modification<LargeTestModel> {
            it.int assign 2
            it.boolean assign true
        }.cycle()
        assertEquals(Modification.Nothing<LargeTestModel>(), Modification.serializer(LargeTestModel.serializer()).default())
        Modification.Nothing<LargeTestModel>().cycle()
        modification<LargeTestModel> {it.intNullable.notNull += 1 }.cycle()
        modification<LargeTestModel> {it.int assign 2 }.cycle()
        modification<LargeTestModel> {it.int coerceAtMost 2 }.cycle()
        modification<LargeTestModel> {it.int coerceAtLeast 2 }.cycle()
        modification<LargeTestModel> {it.int += 2 }.cycle()
        modification<LargeTestModel> {it.int *= 2 }.cycle()
        modification<LargeTestModel> {it.string += "asdf" }.cycle()
        modification<LargeTestModel> {it.trimmedString += "asdf" }.cycle()
        modification<LargeTestModel> {it.caselessString += "asdf" }.cycle()
        modification<LargeTestModel> {it.trimmedCaselessString += "asdf" }.cycle()
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

    private inline fun <reified T> T.cycleItem() {
        println("----$this----")
        run {
            val asString = myJson.encodeToString(this)
            println("JSON: $asString")
            val recreated = myJson.decodeFromString<T>(asString)
            assertEquals<T>(this, recreated)
        }
        run {
            val asString = myProperties.encodeToStringMap(this)
            println("Properties: $asString")
            val recreated = myProperties.decodeFromStringMap<T>(asString)
            assertEquals<T>(this, recreated)
        }
//        run {
//            val asString = myProtobuf.encodeToHexString(this)
//            println("Protobuf: $asString")
//            val recreated = myProtobuf.decodeFromHexString<T>(asString)
//            assertEquals<T>(this, recreated)
//        }
    }

    private inline fun <reified T> Condition<T>.cycle() {
        cycleItem()
        Query(condition = this).cycleItem()
    }
    private inline fun <reified T> Modification<T>.cycle() = cycleItem()
    private inline fun <reified T> List<SortPart<T>>.cycle() = cycleItem()
    private inline fun <reified T> DataClassPathPartial<T>.cycle() = Box(this).cycleItem()

    @Serializable
    data class Box<T>(val value: T)

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