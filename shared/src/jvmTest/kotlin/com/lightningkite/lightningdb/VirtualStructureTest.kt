package com.lightningkite.lightningdb

import com.lightningkite.UUID
import com.lightningkite.lightningdb.testing.LargeTestModel
import com.lightningkite.lightningdb.testing.SampleA
import com.lightningkite.uuid
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import kotlin.test.Test
import kotlin.time.Duration

class VirtualStructureTest {
    @Test fun testStructure() {
        prepareModels()
        com.lightningkite.lightningdb.testing.prepareModels()
        val vtype = LargeTestModel.serializer().makeVirtualType() as VirtualStructure
        println(vtype.annotations)
        val json = Json { serializersModule = ClientModule; encodeDefaults = true }
        println("Schema: ${json.encodeToString(vtype)}")
        val original = LargeTestModel()
        println(original)
        val string = json.encodeToString(LargeTestModel.serializer(), original)
        println(string)
        val vinst = json.decodeFromString(vtype, string)
        println(vtype)
        println(vinst)
        val newString = json.encodeToString(vtype, vinst)
        println(newString)
        assertEquals(string, newString)
    }
    @Test fun testEnum() {
        prepareModels()
        com.lightningkite.lightningdb.testing.prepareModels()
        val vtype = SampleA.serializer().makeVirtualType() as VirtualEnum
        val json = Json { serializersModule = ClientModule; encodeDefaults = true }
        val original = SampleA.B
        println(original)
        val string = json.encodeToString(SampleA.serializer(), original)
        println(string)
        val vinst = json.decodeFromString(vtype, string)
        println(vtype)
        println(vinst)
        val newString = json.encodeToString(vtype, vinst)
        println(newString)
        assertEquals(string, newString)
    }

    @Test fun testVirtualDefault() {
        val vtype = LargeTestModel.serializer().makeVirtualType() as VirtualStructure
        val json = Json { serializersModule = ClientModule; encodeDefaults = true }
        println(json.encodeToString(vtype, vtype()))
    }

    @Serializable
    data class TestModel(
        val _id: UUID = uuid(),
        val x: Int = 0,
        val y: String,
        val z: Duration?,
        val uhoh: UUID,
        val nah: Instant
    )

    @Test
    fun testDefaults() {
        println(TestModel.serializer().default())
    }
}