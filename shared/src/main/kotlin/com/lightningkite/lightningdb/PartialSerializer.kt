package com.lightningkite.lightningdb

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.*
import kotlinx.serialization.internal.GeneratedSerializer

@OptIn(InternalSerializationApi::class)
class PartialSerializer<T>(val source: GeneratedSerializer<T>): KSerializer<Map<String, Any?>> {
    private val childSerializers = source.childSerializers()
    override val descriptor: SerialDescriptor
        get() {
            val sourceDescriptor = source.descriptor
            return buildClassSerialDescriptor("Partial<${sourceDescriptor.serialName}>", sourceDescriptor) {
                for(index in 0 until sourceDescriptor.elementsCount) {
                    element(
                        sourceDescriptor.getElementName(index),
                        sourceDescriptor.getElementDescriptor(index),
                        sourceDescriptor.getElementAnnotations(index),
                        isOptional = true
                    )
                }
            }
        }

    override fun deserialize(decoder: Decoder): Map<String, Any?> = decoder.decodeStructure(descriptor) {
        val out = HashMap<String, Any?>()
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break
                CompositeDecoder.UNKNOWN_NAME -> continue
                else -> decodeSerializableElement(descriptor, index, childSerializers[index])
            }
        }
        out
    }

    override fun serialize(encoder: Encoder, value: Map<String, Any?>) {
        encoder.encodeStructure(descriptor) {
            for((key, v) in value) {
                val index = descriptor.getElementIndex(key)
                encodeSerializableElement(descriptor, index, childSerializers[index] as KSerializer<Any?>, v)
            }
        }
    }
}
