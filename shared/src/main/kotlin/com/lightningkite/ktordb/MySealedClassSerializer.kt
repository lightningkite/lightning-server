@file:OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)

package com.lightningkite.ktordb

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlin.IllegalStateException
import kotlin.reflect.KClass

public class MySealedClassSerializer<T : Any>(
    serialName: String,
    val baseClass: KClass<T>,
    val getSerializerMap: () -> Map<String, KSerializer<out T>>,
    val getName: (T) -> String
) : KSerializer<T> {
    private val serializerMap by lazy { getSerializerMap() }
    private val indexToName by lazy { serializerMap.keys.toTypedArray() }
    private val nameToIndex by lazy { indexToName.withIndex().associate { it.value to it.index } }
    private val serializers by lazy { indexToName.map { serializerMap[it]!! }.toTypedArray() }
    private fun getIndex(item: T): Int = nameToIndex[getName(item)]
        ?: throw IllegalStateException("No serializer inside ${descriptor.serialName} found for ${getName(item)}; available: ${indexToName.joinToString()}")

    override val descriptor: SerialDescriptor = defer(serialName, StructureKind.CLASS) {
        buildClassSerialDescriptor(serialName) {
            for (s in serializers) {
                element(s.descriptor.serialName, s.descriptor, isOptional = true)
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
