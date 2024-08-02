package com.lightningkite

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmInline

interface IsRawString {
    val raw: String
}

object TrimOnSerialize: KSerializer<String> {
    override fun deserialize(decoder: Decoder): String = decoder.decodeString().trim()
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("TrimmedString", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}
object LowercaseOnSerialize: KSerializer<String> {
    override fun deserialize(decoder: Decoder): String = decoder.decodeString().lowercase()
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("CaselessString", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}
object TrimLowercaseOnSerialize: KSerializer<String> {
    override fun deserialize(decoder: Decoder): String = decoder.decodeString().trim().lowercase()
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("CaselessString", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}


object TrimmedStringSerializer : KSerializer<TrimmedString> {
    override fun deserialize(decoder: Decoder): TrimmedString = decoder.decodeString().trimmed()
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("TrimmedString", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: TrimmedString) = encoder.encodeString(value.raw)
}

@Serializable(TrimmedStringSerializer::class)
@JvmInline
value class TrimmedString @Deprecated("Use String.trimmed()") constructor(override val raw: String) : Comparable<TrimmedString>, IsRawString {
    override fun compareTo(other: TrimmedString): Int = raw.compareTo(other.raw)
    override fun toString(): String = raw
}

inline fun TrimmedString.map(action: (String) -> String): TrimmedString = raw.let(action).trimmed()

@Suppress("DEPRECATION")
inline fun String.trimmed(): TrimmedString = TrimmedString(this.trim())




object CaselessStringSerializer : KSerializer<CaselessString> {
    override fun deserialize(decoder: Decoder): CaselessString = decoder.decodeString().caseless()
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("CaselessString", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: CaselessString) = encoder.encodeString(value.raw)
}

@Serializable(CaselessStringSerializer::class)
@JvmInline
value class CaselessString @Deprecated("Use String.caseless()") constructor(override val raw: String) : Comparable<CaselessString>, IsRawString {
    override fun compareTo(other: CaselessString): Int = raw.compareTo(other.raw)
    override fun toString(): String = raw
}

inline fun CaselessString.map(action: (String) -> String): CaselessString = raw.let(action).caseless()

@Suppress("DEPRECATION")
inline fun String.caseless(): CaselessString = CaselessString(this.lowercase())




object TrimmedCaselessStringSerializer : KSerializer<TrimmedCaselessString> {
    override fun deserialize(decoder: Decoder): TrimmedCaselessString = decoder.decodeString().trimmedCaseless()
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("TrimmedCaselessString", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: TrimmedCaselessString) = encoder.encodeString(value.raw)
}

@Serializable(TrimmedCaselessStringSerializer::class)
@JvmInline
value class TrimmedCaselessString @Deprecated("Use String.trimmedCaseless()") constructor(override val raw: String) : Comparable<TrimmedCaselessString>, IsRawString {
    override fun compareTo(other: TrimmedCaselessString): Int = raw.compareTo(other.raw)
    override fun toString(): String = raw
}

inline fun TrimmedCaselessString.map(action: (String) -> String): TrimmedCaselessString = raw.let(action).trimmedCaseless()

@Suppress("DEPRECATION")
inline fun String.trimmedCaseless(): TrimmedCaselessString = TrimmedCaselessString(this.trim().lowercase())


