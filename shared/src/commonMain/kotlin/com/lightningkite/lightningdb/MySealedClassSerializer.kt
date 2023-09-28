@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningdb

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.internal.GeneratedSerializer
import kotlinx.serialization.json.JsonNames
import kotlin.IllegalStateException
import kotlin.reflect.KClass

interface MySealedClassSerializerInterface<T: Any>: KSerializer<T> {
    val options: List<MySealedClassSerializer.Option<T, out T>>
}

class MySealedClassSerializer<T : Any>(
    serialName: String,
    options: () -> List<Option<T, out T>>,
    val annotations: List<Annotation> = listOf(),
) : MySealedClassSerializerInterface<T> {

    class Option<Base, T: Base>(
        val serializer: KSerializer<T>,
        val alternativeNames: Set<String> = setOf(),
        val isInstance: (Base) -> Boolean,
    )

    override val options by lazy(options)

    private val nameToIndex by lazy {
        this.options.flatMapIndexed { index, it ->
            (listOf(it.serializer.descriptor.serialName) + it.alternativeNames)
                .map { n -> n to index }
        }.associate { it }
    }
    private fun getIndex(item: T): Int = options.indexOfFirst { it.isInstance(item) }
        .also {
            if(it == -1)
                throw IllegalStateException("No serializer inside ${descriptor.serialName} found for ${item}")
        }

    override val descriptor: SerialDescriptor = defer(serialName, StructureKind.CLASS) {
        buildClassSerialDescriptor(serialName) {
            this.annotations = this@MySealedClassSerializer.annotations
            for ((index, s) in this@MySealedClassSerializer.options.withIndex()) {
                element(s.serializer.descriptor.serialName, s.serializer.descriptor, isOptional = true, annotations = this@MySealedClassSerializer.options[index].alternativeNames
                    ?.let { listOf(JsonNames(*it.toTypedArray())) } ?: listOf())
            }
        }
    }

    override fun deserialize(decoder: Decoder): T {
        return decoder.decodeStructure(descriptor) {
            val index = decodeElementIndex(descriptor)
            if (index == CompositeDecoder.DECODE_DONE) throw SerializationException("Single key expected, but received none.")
            if (index == CompositeDecoder.UNKNOWN_NAME) throw SerializationException("Unknown key received.")
            val result = decodeSerializableElement(descriptor, index, options[index].serializer)
            if(decodeElementIndex(descriptor) != CompositeDecoder.DECODE_DONE) throw SerializationException("Single key expected, but received multiple.")
            result
        }
    }

    override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeStructure(descriptor) {
            println("$this Serializing ${value}, options are ${options.joinToString { it.serializer.descriptor.serialName }}.  Source seems to be ${options.find { it.serializer.descriptor.serialName == "Equal" }?.serializer?.descriptor?.kind}")
            val index = getIndex(value)
            @Suppress("UNCHECKED_CAST")
            this.encodeSerializableElement<Any?>(descriptor, index, options[index].serializer as KSerializer<Any?>, value)
        }
    }
}
