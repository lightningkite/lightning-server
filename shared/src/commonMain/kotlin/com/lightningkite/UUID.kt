@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.lightningkite

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@OptIn(ExperimentalSerializationApi::class)
object DeferToContextualUuidSerializer: KSerializer<UUID> {
    override val descriptor: SerialDescriptor = object: SerialDescriptor {
        @ExperimentalSerializationApi
        override val elementsCount: Int get() = 1

        @ExperimentalSerializationApi
        override val kind: SerialKind get() = SerialKind.CONTEXTUAL

        @ExperimentalSerializationApi
        override val serialName: String = "com.lightningkite.UUID"

        @ExperimentalSerializationApi
        override fun getElementAnnotations(index: Int): List<Annotation> = listOf()

        @ExperimentalSerializationApi
        override fun getElementDescriptor(index: Int): SerialDescriptor = throw IndexOutOfBoundsException()

        @ExperimentalSerializationApi
        override fun getElementIndex(name: String): Int = -1

        @ExperimentalSerializationApi
        override fun getElementName(index: Int): String = ""

        @ExperimentalSerializationApi
        override fun isElementOptional(index: Int): Boolean = true
    }

    override fun deserialize(decoder: Decoder): UUID {
        val ser = decoder.serializersModule.getContextual(UUID::class)
        return if(ser == null) UUID.parse(decoder.decodeString())
        else decoder.decodeSerializableValue(ser)
    }

    override fun serialize(encoder: Encoder, value: UUID) {
        val ser = encoder.serializersModule.getContextual(UUID::class)
        return if(ser == null) encoder.encodeString(value.toString())
        else encoder.encodeSerializableValue(ser, value)
    }

}

@Serializable(DeferToContextualUuidSerializer::class)
expect class UUID: Comparable<UUID> {
    override fun compareTo(other: UUID): Int
    companion object {
        fun random(): UUID
        fun parse(uuidString: String): UUID
    }
}
@Deprecated("Use UUID.v4() instead", ReplaceWith("UUID.v4()", "com.lightningkite.UUID")) fun uuid(): UUID = UUID.random()
@Deprecated("Use UUID.parse(string) instead", ReplaceWith("UUID.parse(string)", "com.lightningkite.UUID")) fun uuid(string: String): UUID = UUID.parse(string)


