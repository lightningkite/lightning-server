@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.serialization

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encoding.*

class PartialSerializer<T>(val source: KSerializer<T>): KSerializer<Partial<T>> {
    private val childSerializers by lazy {
        this.source.serializableProperties?.map {
            if (it.serializer.descriptor.isNullable) {
                val nn = it.serializer.nullElement()!!
                if (nn.serializableProperties != null) {
                    PartialSerializer(nn).nullable
                } else it.serializer
            } else {
                if (it.serializer.serializableProperties != null) {
                    PartialSerializer(it.serializer)
                } else it.serializer
            }
        } ?: listOf()
    }
    override val descriptor: SerialDescriptor by lazy {
            try {
                val sourceDescriptor = source.descriptor
                buildClassSerialDescriptor("com.lightningkite.serialization.Partial", sourceDescriptor) {
                    if(sourceDescriptor.elementsCount != childSerializers.size) {
                        throw IllegalStateException("Mismatch in child serializer count; ${sourceDescriptor.elementsCount} vs ${childSerializers.size}; ${sourceDescriptor.elementNames.joinToString()} vs ${childSerializers.joinToString { it.descriptor.serialName }}")
                    }
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
        val out = HashMap<SerializableProperty<T, *>, Any?>()
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break
                CompositeDecoder.UNKNOWN_NAME -> continue
                else -> out[source.serializableProperties!!.find { it.name == descriptor.getElementName(index) }!!] = decodeSerializableElement(descriptor, index, childSerializers[index])
            }
        }
        Partial(out)
    }

    override fun serialize(encoder: Encoder, value: Partial<T>) = encoder.encodeStructure(descriptor) {
        for((key, v) in value.parts) {
            val index = descriptor.getElementIndex(key.name)
            @Suppress("UNCHECKED_CAST")
            encodeSerializableElement(descriptor, index, childSerializers[index] as KSerializer<Any?>, v)
        }
    }
}

fun <K> DataClassPathPartial<K>.setMap(key: K, out: Partial<K>) {
    if(properties.isEmpty()) throw IllegalStateException("Path $this cannot be set for partial")
    @Suppress("UNCHECKED_CAST")
    var current = out as Partial<Any?>
    var value: Any? = key
    for (prop in properties.dropLast(1)) {
        @Suppress("UNCHECKED_CAST")
        value = (prop as SerializableProperty<Any?, Any?>).get(value)
        if(value == null) {
            current.parts[prop] = null
            return
        }
        @Suppress("UNCHECKED_CAST")
        current = current.parts.getOrPut(prop as SerializableProperty<Any?, *>) { Partial<Any?>() } as Partial<Any?>
    }
    @Suppress("UNCHECKED_CAST")
    current.parts[properties.last() as SerializableProperty<Any?, *>] = getAny(key)
}
