package com.lightningkite

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmInline

interface IsRawString : Comparable<IsRawString> {
    val raw: String
    fun mapRaw(action: (String) -> String): IsRawString
    override fun compareTo(other: IsRawString): Int = raw.compareTo(other.raw)

    companion object {
        val serialNames = setOf(
            "TrimmedString",
            "CaselessString",
            "TrimmedCaselessString",
        )
    }
}

object TrimOnSerialize: KSerializer<String> {
    override fun deserialize(decoder: Decoder): String = decoder.decodeString().trim()
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("TrimOnSerialize", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}
object LowercaseOnSerialize: KSerializer<String> {
    override fun deserialize(decoder: Decoder): String = decoder.decodeString().lowercase()
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LowercaseOnSerialize", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}
object TrimLowercaseOnSerialize: KSerializer<String> {
    override fun deserialize(decoder: Decoder): String = decoder.decodeString().trim().lowercase()
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("TrimLowercaseOnSerialize", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}


object TrimmedStringSerializer : KSerializer<TrimmedString> {
    override fun deserialize(decoder: Decoder): TrimmedString = decoder.decodeString().trimmed()
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("TrimmedString", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: TrimmedString) = encoder.encodeString(value.raw)
}

@Serializable(TrimmedStringSerializer::class)
@JvmInline
value class TrimmedString @Deprecated("Use String.trimmed()") constructor(override val raw: String) : IsRawString {

    override fun toString(): String = raw
    override fun mapRaw(action: (String) -> String): TrimmedString = raw.let(action).trimmed()
}

@Suppress("DEPRECATION", "NOTHING_TO_INLINE")
inline fun String.trimmed(): TrimmedString = TrimmedString(this.trim())




object CaselessStringSerializer : KSerializer<CaselessString> {
    override fun deserialize(decoder: Decoder): CaselessString = decoder.decodeString().caseless()
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("CaselessString", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: CaselessString) = encoder.encodeString(value.raw)
}

@Serializable(CaselessStringSerializer::class)
@JvmInline
value class CaselessString @Deprecated("Use String.caseless()") constructor(override val raw: String) : IsRawString {
    override fun toString(): String = raw
    override fun mapRaw(action: (String) -> String): CaselessString = raw.let(action).caseless()
}

@Suppress("DEPRECATION", "NOTHING_TO_INLINE")
inline fun String.caseless(): CaselessString = CaselessString(this.lowercase())




object TrimmedCaselessStringSerializer : KSerializer<TrimmedCaselessString> {
    override fun deserialize(decoder: Decoder): TrimmedCaselessString = decoder.decodeString().trimmedCaseless()
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("TrimmedCaselessString", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: TrimmedCaselessString) = encoder.encodeString(value.raw)
}

@Serializable(TrimmedCaselessStringSerializer::class)
@JvmInline
value class TrimmedCaselessString @Deprecated("Use String.trimmedCaseless()") constructor(override val raw: String) : IsRawString {
    override fun toString(): String = raw
    override fun mapRaw(action: (String) -> String): TrimmedCaselessString = raw.let(action).trimmedCaseless()
}

@Suppress("DEPRECATION", "NOTHING_TO_INLINE")
inline fun String.trimmedCaseless(): TrimmedCaselessString = TrimmedCaselessString(this.trim().lowercase())





object EmailAddressSerializer : KSerializer<EmailAddress> {
    override fun deserialize(decoder: Decoder): EmailAddress = decoder.decodeString().toEmailAddress()
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("EmailAddress", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: EmailAddress) = encoder.encodeString(value.raw)
}

@Serializable(EmailAddressSerializer::class)
@JvmInline
value class EmailAddress @Deprecated("Use String.toEmailAddress()") constructor(override val raw: String) : IsRawString {
    override fun toString(): String = raw
    val domain get() = raw.substringAfter('@')
    val localPart get() = raw.substringBefore('@').substringBefore('+')
    val account get() = raw.substringBefore('@').substringBefore('+').filter { it.isLetter() }
    val plusAddress get() = raw.substringBefore('+', "")
    fun toBaseAccount() = "$account@$domain".toEmailAddress()
    val url: String get() = "mailto:$raw"
    override fun mapRaw(action: (String) -> String): EmailAddress = raw.let(action).toEmailAddress()
}

private val emailRegex = Regex("(?:[a-z0-9!#\$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#\$%&'*+/=?^_`{|}~-]+)*|\"(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21\\x23-\\x5b\\x5d-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])*\")@(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?|\\[(?:(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9]))\\.){3}(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9])|[a-z0-9-]*[a-z0-9]:(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21-\\x5a\\x53-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])+)\\])")
@Suppress("DEPRECATION")
fun String.toEmailAddress(): EmailAddress {
    val fixed = this.trim().lowercase()
    if (emailRegex.matches(fixed)) return EmailAddress(fixed)
    else throw IllegalArgumentException("$fixed is not an email address.")
}


object PhoneNumberSerializer : KSerializer<PhoneNumber> {
    override fun deserialize(decoder: Decoder): PhoneNumber = decoder.decodeString().toPhoneNumber()
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("PhoneNumber", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: PhoneNumber) = encoder.encodeString(value.raw)
}

@Serializable(PhoneNumberSerializer::class)
@JvmInline
/**
 * RFC 2806
 * '+' + country code + subscriber number, no parens/spacing/other formatting
 * Assumes US by default when parsing.
 */
value class PhoneNumber @Deprecated("Use String.toPhoneNumber()") constructor(override val raw: String) : IsRawString {
    // TODO: Would be nice to have more local formats here, or all?  Keep small for now
    override fun toString(): String = when {
        //+18013693729
        //012345678901
        raw.startsWith("+1") -> "+1 (${raw.substring(2, 5)}) ${raw.substring(5, 8)}-${raw.substring(8)}"
        else -> raw
    }
    val url: String get() = "tel:$raw"
    override fun mapRaw(action: (String) -> String): PhoneNumber = raw.let(action).toPhoneNumber()
}

private val punctuation = setOf(' ', '-', '.', ',', '(', ')', '[', ']', '<', '>')

@Suppress("DEPRECATION")
fun String.toPhoneNumber(): PhoneNumber {
    val fixed = this.lowercase().filter { it !in punctuation }
    if(fixed.startsWith('+')) {
        return PhoneNumber(fixed)
    } else if(fixed.length == 10) {
        return PhoneNumber("+1$fixed")
    } else {
        throw IllegalArgumentException("Phone numbers should begin with a '+' and your country code.")
    }
}
