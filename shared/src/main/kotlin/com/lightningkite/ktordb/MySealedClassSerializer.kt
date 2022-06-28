@file:OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)

package com.lightningkite.ktordb

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.JsonNames
import kotlin.IllegalStateException
import kotlin.reflect.KClass

public class MySealedClassSerializer<T : Any>(
    serialName: String,
    val baseClass: KClass<T>,
    private val getSerializerMap: () -> Map<String, KSerializer<out T>>,
    val alternateReadNames: Map<String, String> = mapOf(),
    val annotations: List<Annotation> = listOf(),
    val getName: (T) -> String
) : KSerializer<T> {

    val serializerMap by lazy { getSerializerMap() }
    private val indexToName by lazy { serializerMap.keys.toTypedArray() }
    private val nameToIndex by lazy {
        val map = HashMap<String, Int>()
        indexToName.withIndex().forEach { map[it.value] = it.index }
        alternateReadNames.forEach {
            map[it.value]?.let { o -> map[it.key] = o }
        }
        map
    }
    private val serializers by lazy { indexToName.map { serializerMap[it]!! }.toTypedArray() }
    private fun getIndex(item: T): Int = nameToIndex[getName(item)]
        ?: throw IllegalStateException("No serializer inside ${descriptor.serialName} found for ${getName(item)}; available: ${indexToName.joinToString()}")

    override val descriptor: SerialDescriptor = defer(serialName, StructureKind.CLASS) {
        buildClassSerialDescriptor(serialName) {
            this.annotations = this@MySealedClassSerializer.annotations
            val reversedAlternates = alternateReadNames.entries.groupBy { it.value }.mapValues { it.value.map { it.key } }
            for ((index, s) in serializers.withIndex()) {
                element(s.descriptor.serialName, s.descriptor, isOptional = true, annotations = indexToName[index].let { reversedAlternates[it] }
                    ?.let { listOf(JsonNames(*it.toTypedArray())) } ?: listOf())
            }
        }
    }

    override fun deserialize(decoder: Decoder): T {
        return decoder.decodeStructure(descriptor) {
            val index = decodeElementIndex(descriptor)
            if (index == CompositeDecoder.DECODE_DONE) throw IllegalStateException()
            if (index == CompositeDecoder.UNKNOWN_NAME) throw IllegalStateException()
            val result = decodeSerializableElement(descriptor, index, serializers[index])
            assert(decodeElementIndex(descriptor) == CompositeDecoder.DECODE_DONE)
            result
        }
    }

    override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeStructure(descriptor) {
            val index = getIndex(value)
            @Suppress("UNCHECKED_CAST")
            this.encodeSerializableElement<Any?>(descriptor, index, serializers[index] as KSerializer<Any?>, value)
        }
    }
}
