package com.lightningkite.lightningserver.files

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object ServerFileSerializer : KSerializer<ServerFile> {
    //Description("A URL referencing a file that the server owns.")
    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = object: SerialDescriptor {
        override val kind: SerialKind = PrimitiveKind.STRING
        override val serialName: String = "com.lightningkite.lightningserver.files.ServerFile"
        override val elementsCount: Int get() = 0
        override fun getElementName(index: Int): String = error()
        override fun getElementIndex(name: String): Int = error()
        override fun isElementOptional(index: Int): Boolean = error()
        override fun getElementDescriptor(index: Int): SerialDescriptor = error()
        override fun getElementAnnotations(index: Int): List<Annotation> = error()
        override fun toString(): String = "PrimitiveDescriptor($serialName)"
        private fun error(): Nothing = throw IllegalStateException("Primitive descriptor does not have elements")
        override val annotations: List<Annotation> = listOf()
    }

    override fun serialize(encoder: Encoder, value: ServerFile) {
        encoder.encodeString(value.location)
    }

    override fun deserialize(decoder: Decoder): ServerFile {
        return ServerFile(decoder.decodeString())
    }
}