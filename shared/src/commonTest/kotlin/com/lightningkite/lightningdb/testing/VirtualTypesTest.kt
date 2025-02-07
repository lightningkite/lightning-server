package com.lightningkite.lightningdb.testing

import com.lightningkite.*
import com.lightningkite.serialization.*
import kotlinx.datetime.Instant
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration
import kotlin.time.measureTime

@OptIn(ExperimentalSerializationApi::class)
class VirtualTypesTest {

    init {
        prepareModelsShared()
        prepareModelsSharedTest()
    }
    fun <T> testVirtualVersion(serializer: KSerializer<T>, instance: T, builderAction: JsonBuilder.()->Unit = {}) {
        val virtualRegistry = SerializationRegistry.master.virtualize { it.contains("testing") }
        val vtype = virtualRegistry.virtualTypes[serializer.descriptor.serialName] as VirtualStruct
        val vtypeSerializer = virtualRegistry[serializer.descriptor.serialName, serializer.tryTypeParameterSerializers3() ?: arrayOf()] as VirtualStruct.Concrete
        println(vtypeSerializer.serializers)
        println(vtype.annotations)
        val json = Json {
            serializersModule = ClientModule
            encodeDefaults = true
            allowStructuredMapKeys = true
            builderAction()
        }
        println("Schema: ${json.encodeToString(vtype)}")
        val original = instance
        // forward
        json.decodeFromString(vtypeSerializer, json.encodeToString(serializer, original).also { println(it) })

        val string = json.encodeToString(serializer, original)
        println(string)
        val vinst = json.decodeFromString(vtypeSerializer, string)
        println(vtype)
        println(vinst)
        val newString = json.encodeToString(vtypeSerializer, vinst)
        println(newString)
        assertEquals(
            json.decodeFromString(serializer, string).toString().split(',').joinToString(",\n"),
            json.decodeFromString(serializer, newString).toString().split(',').joinToString(",\n")
        )
        assertEquals(
            json.decodeFromString(serializer, string),
            json.decodeFromString(serializer, newString)
        )

        // reverse
        json.decodeFromString(serializer, json.encodeToString(vtypeSerializer, vinst))

//        measureTime {
//            repeat(10000) {
//                json.encodeToString(vtypeSerializer, json.decodeFromString(vtypeSerializer, string))
//            }
//        }.also { println("Performance: ${it / 10000}") }
    }
    @Test fun testSerializableAnnotation() {
        val serializer = LargeTestModel.serializer()

        val virtualRegistry = SerializationRegistry.master.virtualize { it.contains("testing") }
        val vtype = virtualRegistry.virtualTypes[serializer.descriptor.serialName] as VirtualStruct
        val vtypeSerializer = virtualRegistry[serializer.descriptor.serialName, serializer.tryTypeParameterSerializers3() ?: arrayOf()] as VirtualStruct.Concrete

        assertEquals(
            vtype.fields.find { it.name == "string" }!!.annotations,
            vtypeSerializer.serializableProperties.find { it.name == "string" }!!.serializableAnnotations
        )
    }
    @Test fun testStructure() {
        LargeTestModel_list
        testVirtualVersion(LargeTestModel.serializer(), LargeTestModel())
    }
    @Test fun testStructureDefaultsRegen() {
        val serializer = LargeTestModel.serializer()
        val virtualRegistry = SerializationRegistry.master.virtualize { it.contains("testing") }
        val vtype = virtualRegistry.virtualTypes[serializer.descriptor.serialName] as VirtualStruct
        val vtypeSerializer = virtualRegistry[serializer.descriptor.serialName, serializer.tryTypeParameterSerializers3() ?: arrayOf()] as VirtualStruct.Concrete
        (0..<vtypeSerializer.descriptor.elementsCount)
            .forEach {
                println(vtypeSerializer.descriptor.getElementName(it) + ": " + vtypeSerializer.descriptor.isElementOptional(it))
            }
        // Seems odd, right?
        // We're checking that the IDs are regenerated randomly as intended
        assertNotEquals(vtypeSerializer.default._id, vtypeSerializer.default._id)
    }
    @Test fun testStructureWithoutDefaults() {
        testVirtualVersion(LargeTestModel.serializer(), LargeTestModel()) { encodeDefaults = false }
    }
    @Test fun testGeneric() {
        testVirtualVersion(
            GenericBox.serializer(Int.serializer()),
            GenericBox(value = 1, nullable = 2, list = listOf(3, 4))
        )
        testVirtualVersion(
            GenericBox.serializer(Int.serializer()),
            GenericBox(value = 1, nullable = 2, list = listOf(3, 4))
        )
    }
//    @Test fun testEnum() {
//        val vtype = SampleA.serializer().makeVirtualType() as VirtualEnum
//        val json = Json { serializersModule = ClientModule; encodeDefaults = true }
//        val original = SampleA.B
//        println(original)
//        val string = json.encodeToString(SampleA.serializer(), original)
//        println(string)
//        val vinst = json.decodeFromString(vtype, string)
//        println(vtype)
//        println(vinst)
//        val newString = json.encodeToString(vtype, vinst)
//        println(newString)
//        assertEquals(string, newString)
//    }
//
//    @Test fun testVirtualDefault() {
//        val vtype = LargeTestModel.serializer().makeVirtualType() as VirtualStructure
//        val json = Json { serializersModule = ClientModule; encodeDefaults = true }
//        println(json.encodeToString(vtype, vtype()))
//    }

    @Serializable
    data class TestModel(
        val _id: UUID = UUID.random(),
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