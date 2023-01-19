@file:OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)

package com.lightningkite.lightningdb

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

class SortPartSerializer<T>(val inner: KSerializer<T>): KSerializer<SortPart<T>> {
    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = object: SerialDescriptor {
        override val kind: SerialKind = PrimitiveKind.STRING
        override val serialName: String = "SortPart<${inner.descriptor.serialName}>"
        override val elementsCount: Int get() = 0
        override fun getElementName(index: Int): String = error()
        override fun getElementIndex(name: String): Int = error()
        override fun isElementOptional(index: Int): Boolean = error()
        override fun getElementDescriptor(index: Int): SerialDescriptor = error()
        override fun getElementAnnotations(index: Int): List<Annotation> = error()
        override fun toString(): String = "PrimitiveDescriptor($serialName)"
        private fun error(): Nothing = throw IllegalStateException("Primitive descriptor does not have elements")
        override val annotations: List<Annotation> = SortPart::class.annotations
    }

    val fields = inner.attemptGrabFields()
    val sub = KeyPathSerializer(inner)

    override fun deserialize(decoder: Decoder): SortPart<T> {
        val value = decoder.decodeString()
        val descending = value.startsWith('-')
        val nameWithoutCase = value.removePrefix("-")
        val ignoreCase = nameWithoutCase.startsWith('*')
        val name = nameWithoutCase.removePrefix("*")
        return SortPart(sub.fromString(name), !descending, ignoreCase)
    }

    override fun serialize(encoder: Encoder, value: SortPart<T>) {
        encoder.encodeString(buildString {
            if(!value.ascending) append('-')
            if(value.ignoreCase) append('*')
            append(value.field.toString())
        })
    }
}