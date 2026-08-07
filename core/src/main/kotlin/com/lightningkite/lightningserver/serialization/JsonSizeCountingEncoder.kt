package com.lightningkite.lightningserver.serialization

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.SerializersModule

/** Thrown to abandon measurement once the answer can no longer change the caller's decision. */
internal object BudgetExhausted : Throwable(null, null, false, false)

/**
 * An [kotlinx.serialization.encoding.Encoder] that writes nothing and counts what it would have
 * written as JSON. See [approximateJsonSize], which is how you should reach it.
 *
 * Stops early once [limit] is reached, so measuring an enormous value costs no more than a small one.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class JsonSizeCountingEncoder(
    override val serializersModule: SerializersModule,
    private val limit: Int,
) : AbstractEncoder() {

    var size: Int = 0
        private set

    private fun add(bytes: Int) {
        size += bytes
        if (size >= limit) throw BudgetExhausted
    }

    override fun encodeBoolean(value: Boolean): Unit = add(if (value) 4 else 5)
    override fun encodeByte(value: Byte): Unit = add(digitCount(value.toLong()))
    override fun encodeShort(value: Short): Unit = add(digitCount(value.toLong()))
    override fun encodeInt(value: Int): Unit = add(digitCount(value.toLong()))
    override fun encodeLong(value: Long): Unit = add(digitCount(value))

    // Formatting a float exactly would mean allocating its string, which is the cost this class exists
    // to avoid; typical JSON output is well under this.
    override fun encodeFloat(value: Float): Unit = add(12)
    override fun encodeDouble(value: Double): Unit = add(12)

    override fun encodeChar(value: Char): Unit = add(3)

    // Quotes, plus the characters themselves. Escaping can only add, so this may under-count slightly
    // for strings full of quotes or control characters.
    override fun encodeString(value: String): Unit = add(value.length + 2)

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int): Unit =
        add(enumDescriptor.getElementName(index).length + 2)

    override fun encodeNull(): Unit = add(4)

    /** Reached only for types with no more specific hook. */
    override fun encodeValue(value: Any): Unit = add(16)

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        add(2) // {} or []
        return this
    }

    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT ->
                add(descriptor.getElementName(index).length + 4) // "name": and a separating comma
            // Lists and maps have no field names; one byte covers the comma, and for maps the colon
            // averages out across the alternating key and value elements.
            else -> if (index > 0) add(1)
        }
        return true
    }

    private fun digitCount(value: Long): Int {
        if (value == Long.MIN_VALUE) return 20
        var remaining = if (value < 0) -value else value
        var count = if (value < 0) 2 else 1
        while (remaining >= 10) {
            remaining /= 10
            count++
        }
        return count
    }
}
