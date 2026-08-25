package com.lightningkite.lightningserver.audit

/**
 * A set of field bit indices, laid out across four `Int`s.
 *
 * ## Why four `Int`s rather than a `Long` or a `ByteArray`
 *
 * `Int` is the only type the framework can query bitwise: the entire bitwise condition surface is
 * `Condition<Int>` (`IntBitsSet`, `IntBitsClear`, `IntBitsAnySet`, `IntBitsAnyClear`). A `Long` or
 * `ByteArray` bitfield would be storable but not queryable, so "which requests disclosed the SSN
 * field?" would mean a full scan and client-side filtering — unusable at audit-table volume.
 *
 * Bit index `i` lives in column `i / 32` at bit `i % 32`.
 */
public class FieldBits private constructor(
    public val fields0: Int,
    public val fields1: Int,
    public val fields2: Int,
    public val fields3: Int,
) {
    /** This set plus the field at [index]. */
    public operator fun plus(index: Int): FieldBits {
        require(index in 0 until CAPACITY) { "Bit index $index is outside the 0..${CAPACITY - 1} range." }
        val bit = 1 shl (index % 32)
        return when (index / 32) {
            0 -> FieldBits(fields0 or bit, fields1, fields2, fields3)
            1 -> FieldBits(fields0, fields1 or bit, fields2, fields3)
            2 -> FieldBits(fields0, fields1, fields2 or bit, fields3)
            else -> FieldBits(fields0, fields1, fields2, fields3 or bit)
        }
    }

    /** Union of two sets. */
    public operator fun plus(other: FieldBits): FieldBits = FieldBits(
        fields0 or other.fields0,
        fields1 or other.fields1,
        fields2 or other.fields2,
        fields3 or other.fields3,
    )

    public operator fun contains(index: Int): Boolean {
        require(index in 0 until CAPACITY) { "Bit index $index is outside the 0..${CAPACITY - 1} range." }
        return column(index / 32) and (1 shl (index % 32)) != 0
    }

    /** The indices in this set, ascending. */
    public fun indices(): List<Int> = buildList {
        for (column in 0 until COLUMNS) {
            val bits = column(column)
            if (bits == 0) continue
            for (bit in 0 until 32) if (bits and (1 shl bit) != 0) add(column * 32 + bit)
        }
    }

    public fun column(index: Int): Int = when (index) {
        0 -> fields0
        1 -> fields1
        2 -> fields2
        3 -> fields3
        else -> throw IndexOutOfBoundsException("Column $index does not exist; there are $COLUMNS.")
    }

    public val isEmpty: Boolean get() = fields0 == 0 && fields1 == 0 && fields2 == 0 && fields3 == 0

    override fun equals(other: Any?): Boolean = other is FieldBits &&
        fields0 == other.fields0 && fields1 == other.fields1 &&
        fields2 == other.fields2 && fields3 == other.fields3

    override fun hashCode(): Int =
        ((fields0 * 31 + fields1) * 31 + fields2) * 31 + fields3

    override fun toString(): String = "FieldBits${indices()}"

    public companion object {
        public const val COLUMNS: Int = 4

        /** The number of distinct fields one audited model may have. */
        public const val CAPACITY: Int = COLUMNS * 32

        public val EMPTY: FieldBits = FieldBits(0, 0, 0, 0)

        public fun of(indices: Iterable<Int>): FieldBits = indices.fold(EMPTY, FieldBits::plus)

        public fun ofColumns(fields0: Int, fields1: Int, fields2: Int, fields3: Int): FieldBits =
            FieldBits(fields0, fields1, fields2, fields3)
    }
}
