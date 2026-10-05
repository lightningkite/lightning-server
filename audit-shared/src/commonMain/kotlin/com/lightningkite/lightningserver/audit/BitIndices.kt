package com.lightningkite.lightningserver.audit

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.DataClassPath
import com.lightningkite.services.database.InlineProperty
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
@GenerateDataClassPaths
@JvmInline
/**
 * An [Int] bitmask holding a 'set' of indices in range `0-31` (32 bits).
 *
 * This can be intuitively thought of as a `Set<Int>` where the values are between 0 and 31 inclusive. The indices are
 * read in big-endian order from the bitmask. Attempting to add or get any index not in the range `0-31` will result
 * in an [IndexOutOfBoundsException].
 *
 * ### Examples
 * Only using 4 bits here instead of 32
 * - `0000 <-> setOf()`
 * - `1000 <-> setOf(0)`
 * - `1010 <-> setOf(0, 2)`
 * - `0101 <-> setOf(1, 3)`
 * - `1111 <-> setOf(0, 1, 2, 3)`
 * */
// Note: We use Int here instead of Long because JSON can't support 64-bit integer precision, and Int is queryable
public value class BitIndices(public val mask: Int) : Set<Int> {
    public constructor() : this(0)

    /** Scans through the bits in the mask, returning the indices where the bit is `1`, in big-endian order.*/
    override operator fun iterator(): Iterator<Int> = object : Iterator<Int> {
        var unscanned: Int = mask

        override fun hasNext(): Boolean = unscanned != 0

        override fun next(): Int {
            if (unscanned == 0) throw NoSuchElementException()
            val idx = unscanned.countLeadingZeroBits()
            unscanned = unscanned xor (1 shl MAX_INDEX - idx)
            return idx
        }
    }

    override val size: Int get() = mask.countOneBits()

    override fun isEmpty(): Boolean = mask == 0

    public operator fun get(index: Int): Boolean {
        ensureValidIndex(index)
        return mask and (1 shl MAX_INDEX - index) != 0
    }

    /** Unlike [get], returns `false` for an index outside `0-31` rather than throwing. */
    override fun contains(element: Int): Boolean = element in 0..<CAPACITY && get(element)

    override fun containsAll(elements: Collection<Int>): Boolean {
        var total = 0
        for (idx in elements) {
            if (idx !in 0..<CAPACITY) return false
            total = total or (1 shl MAX_INDEX - idx)
        }
        return (total and mask) == total
    }

    public fun containsAll(indices: BitIndices): Boolean =
        (indices.mask and mask) == indices.mask

    /** Returns the union of the two sets */
    public operator fun plus(other: BitIndices): BitIndices = BitIndices(mask or other.mask)

    public fun toArray(): IntArray {
        val arr = IntArray(size)
        var i = 0
        for (idx in this) arr[i++] = idx
        return arr
    }

    override fun toString(): String = joinToString(prefix = "BitIndices[", postfix = "]")

    public companion object {
        public const val CAPACITY: Int = 32

        public const val MAX_INDEX: Int = CAPACITY - 1

        public fun from(indices: Iterator<Int>): BitIndices {
            var aggregate = 0
            for (idx in indices) {
                ensureValidIndex(idx)
                aggregate = aggregate or (1 shl MAX_INDEX - idx)
            }
            return BitIndices(aggregate)
        }
    }
}

public fun bitIndicesOf(vararg indices: Int): BitIndices = BitIndices.from(indices.iterator())

private fun ensureValidIndex(index: Int) {
    if (index !in 0..<BitIndices.CAPACITY) throw IndexOutOfBoundsException("Index $index is out of range 0..${BitIndices.CAPACITY-1} for BitIndices")
}

public fun <T> DataClassPath<T, BitIndices>.containsAll(indices: BitIndices): Condition<T> =
    @OptIn(InlineProperty::class)
    mask.mapCondition(
        Condition.IntBitsSet(indices.mask)
    )

public fun <T> DataClassPath<T, BitIndices>.containsAny(indices: BitIndices): Condition<T> =
    @OptIn(InlineProperty::class)
    mask.mapCondition(
        Condition.IntBitsAnySet(indices.mask)
    )

