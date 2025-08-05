package com.lightningkite.lightningserver

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(HttpHeaderValueSerializer::class)
public data class HttpHeaderValue(
    val root: String,
    val parameters: Map<String, String>
) {
    public companion object {
        public fun parse(raw: String): HttpHeaderValue {
            val split = raw.splitToSequence(';').map { it.trim() }.filter { it.isNotBlank() }.toList()
            if (split.isEmpty()) return HttpHeaderValue("", mapOf())
            if (split[0].contains('=')) return HttpHeaderValue(
                "",
                split.associate { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() })
            else return HttpHeaderValue(
                split[0],
                split.drop(1).associate { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }
            )
        }
    }

    public fun toHttpString(): String =
        if (root.isEmpty()) (parameters.entries.takeUnless { it.isEmpty() }?.joinToString("; ") {
            if (it.value.isEmpty()) it.key
            else "${it.key}=${it.value}"
        } ?: "")
        else root + (parameters.entries.takeUnless { it.isEmpty() }?.joinToString("; ", "; ") {
            if (it.value.isEmpty()) it.key
            else "${it.key}=${it.value}"
        } ?: "")

    override fun toString(): String = toHttpString()
}

public object HttpHeaderValueSerializer : KSerializer<HttpHeaderValue> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.lightningkite.lightningserver.HttpHeaderValue", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: HttpHeaderValue): Unit = encoder.encodeString(value.toHttpString())
    override fun deserialize(decoder: Decoder): HttpHeaderValue = HttpHeaderValue.parse(decoder.decodeString())
}