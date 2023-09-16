package com.lightningkite.lightningdb

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.*

class PartialSerializer<T>(val source: KSerializer<T>): KSerializer<Partial<T>> {
    private val childSerializers = this.source.childSerializers()!!.map {
        if (it.childSerializers() != null) {
            PartialSerializer(it)
        } else it
    }
    override val descriptor: SerialDescriptor
        get() {
            try {
                val sourceDescriptor = source.descriptor
                return buildClassSerialDescriptor("com.lightningkite.lightningdb.Partial", sourceDescriptor) {
                    for (index in 0 until sourceDescriptor.elementsCount) {
                        val s = childSerializers[index]
                        if (s is PartialSerializer<*>) {
                            element(
                                elementName = sourceDescriptor.getElementName(index),
                                descriptor = s.descriptor,
                                annotations = sourceDescriptor.getElementAnnotations(index),
                                isOptional = true
                            )
                        } else {
                            element(
                                elementName = sourceDescriptor.getElementName(index),
                                descriptor = sourceDescriptor.getElementDescriptor(index),
                                annotations = sourceDescriptor.getElementAnnotations(index),
                                isOptional = true
                            )
                        }
                    }
                }
            } catch(e: Exception) {
                throw Exception("Failed to make partial descriptor for ${source.descriptor.serialName}", e)
            }
        }

    override fun deserialize(decoder: Decoder): Partial<T> = decoder.decodeStructure(descriptor) {
        val out = HashMap<String, Any?>()
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break
                CompositeDecoder.UNKNOWN_NAME -> continue
                else -> out[descriptor.getElementName(index)] = decodeSerializableElement(descriptor, index, childSerializers[index])
            }
        }
        Partial(out)
    }

    override fun serialize(encoder: Encoder, value: Partial<T>) = encoder.encodeStructure(descriptor) {
        for((key, v) in value.parts) {
            val index = descriptor.getElementIndex(key)
            encodeSerializableElement(descriptor, index, childSerializers[index] as KSerializer<Any?>, v)
        }
    }
}

fun <K> DataClassPathPartial<K>.setMap(key: K, out: Partial<K>) {
    if(properties.isEmpty()) throw IllegalStateException("Path ${this} cannot be set for partial")
    var current = out as Partial<Any?>
    for (prop in properties.dropLast(1)) {
        current = current.parts.getOrPut(prop.name) { Partial<Any?>() } as Partial<Any?>
    }
    current.parts[properties.last().name] = getAny(key)
}
