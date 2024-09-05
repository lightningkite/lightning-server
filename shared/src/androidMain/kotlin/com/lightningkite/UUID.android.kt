@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.lightningkite

import kotlinx.serialization.Serializable

@Serializable(DeferToContextualUuidSerializer::class)
actual data class UUID(
    val mostSignificantBits: ULong,
    val leastSignificantBits: ULong
): Comparable<UUID> {
    actual override fun compareTo(other: UUID): Int {
        return if (this.mostSignificantBits != other.mostSignificantBits)
            this.mostSignificantBits.compareTo(other.mostSignificantBits)
        else
            this.leastSignificantBits.compareTo(other.leastSignificantBits)
    }
    actual companion object {
        actual fun random(): UUID = java.util.UUID.randomUUID().let { UUID(it.mostSignificantBits.toULong(), it.leastSignificantBits.toULong()) }
        actual fun parse(uuidString: String): UUID = java.util.UUID.fromString(uuidString).let { UUID(it.mostSignificantBits.toULong(), it.leastSignificantBits.toULong()) }
    }

    override fun toString(): String {
        val bytes = ByteArray(36)
        leastSignificantBits.formatBytesInto(bytes, 24, 6)
        bytes[23] = '-'.code.toByte()
        (leastSignificantBits shr 48).formatBytesInto(bytes, 19, 2)
        bytes[18] = '-'.code.toByte()
        mostSignificantBits.formatBytesInto(bytes, 14, 2)
        bytes[13] = '-'.code.toByte()
        (mostSignificantBits shr 16).formatBytesInto(bytes, 9, 2)
        bytes[8] = '-'.code.toByte()
        (mostSignificantBits shr 32).formatBytesInto(bytes, 0, 4)
        return bytes.decodeToString()
    }
}

/**
 * Converts this [java.util.UUID] value to the corresponding [kotlin.uuid.Uuid] value.
 */
fun java.util.UUID.toKotlinUuid(): UUID =
    UUID(mostSignificantBits.toULong(), leastSignificantBits.toULong())

/**
 * Converts this [kotlin.uuid.Uuid] value to the corresponding [java.util.UUID] value.
 */
fun UUID.toJavaUuid(): java.util.UUID = java.util.UUID(this.mostSignificantBits.toLong(), this.leastSignificantBits.toLong())

@JvmName("UUIDFromLongs")
fun UUID(
    mostSignificantBits: Long,
    leastSignificantBits: Long
)= UUID(mostSignificantBits.toULong(), leastSignificantBits.toULong())

private const val LOWER_CASE_HEX_DIGITS = "0123456789abcdef"
private const val UPPER_CASE_HEX_DIGITS = "0123456789ABCDEF"
private fun ULong.formatBytesInto(dst: ByteArray, dstOffset: Int, count: Int) {
    var long = this
    var dstIndex = dstOffset + 2 * count
    repeat(count) {
        val byte = (long.toLong() and 0xFF).toInt()
        val byteDigits = BYTE_TO_LOWER_CASE_HEX_DIGITS[byte]
        dst[--dstIndex] = byteDigits.toByte()
        dst[--dstIndex] = (byteDigits shr 8).toByte()
        long = long shr 8
    }
}
private val BYTE_TO_LOWER_CASE_HEX_DIGITS = IntArray(256) {
    (LOWER_CASE_HEX_DIGITS[(it shr 4)].code shl 8) or LOWER_CASE_HEX_DIGITS[(it and 0xF)].code
}
