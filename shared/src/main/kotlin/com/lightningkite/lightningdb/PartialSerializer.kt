package com.lightningkite.lightningdb

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.*
import kotlinx.serialization.internal.GeneratedSerializer

@OptIn(InternalSerializationApi::class)
class PartialSerializer<T>(source: KSerializer<T>): KSerializer<Map<String, Any?>> {
    val source = source as GeneratedSerializer<T>
    private val childSerializers = this.source.childSerializers().map {
        if (it is GeneratedSerializer<*>) {
            PartialSerializer(it)
        } else it
    }
    override val descriptor: SerialDescriptor
        get() {
            val sourceDescriptor = source.descriptor
            return buildClassSerialDescriptor("Partial<${sourceDescriptor.serialName}>", sourceDescriptor) {
                for(index in 0 until sourceDescriptor.elementsCount) {
                    val s = childSerializers[index]
                    if(s is PartialSerializer<*>) {
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
        }

    override fun deserialize(decoder: Decoder): Map<String, Any?> = decoder.decodeStructure(descriptor) {
        val out = HashMap<String, Any?>()
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break
                CompositeDecoder.UNKNOWN_NAME -> continue
                else -> out[descriptor.getElementName(index)] = decodeSerializableElement(descriptor, index, childSerializers[index])
            }
        }
        out
    }

    override fun serialize(encoder: Encoder, value: Map<String, Any?>) = encoder.encodeStructure(descriptor) {
        for((key, v) in value) {
            val index = descriptor.getElementIndex(key)
            encodeSerializableElement(descriptor, index, childSerializers[index] as KSerializer<Any?>, v)
        }
    }
}

fun <K> DataClassPathPartial<K>.setMap(key: K, out: MutableMap<String, Any?>) {
    var current = out
    for (prop in properties.dropLast(1)) {
        current = current.getOrPut(prop.name) { HashMap<String, Any?>() } as MutableMap<String, Any?>
    }
    current[properties.last().name] = getAny(key)
}

/*

Apply keypath to map

Get map OR write?

 */