package com.lightningkite.lightningserver.data

import com.lightningkite.services.data.Queryable
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.InlineProperty
import com.lightningkite.services.database.optics.*
import kotlin.jvm.JvmInline

/**
 * A set of indices from `0` until [capacity], stored as the bits of a single number.
 *
 * Index `0` is the most significant bit, so `1010` (in 4 bits) holds `setOf(0, 2)`. Iteration is in ascending order.
 * [get] throws an [IndexOutOfBoundsException] for an index outside the range, while [contains] returns `false`.
 */
// Claude: no default methods here.  A value class calling an inherited default is boxed on every call.
public sealed interface BitIndices : Set<Int> {
    /** The number of indices this can hold. */
    public val capacity: Int

    /**
     * Returns `true` if [index] is in this set.
     *
     * @throws IndexOutOfBoundsException if [index] is not in `0 until capacity`.
     */
    public operator fun get(index: Int): Boolean
}

// Note from hunter: I considered using shared methods to reduce code duplication, but I think the performance of these
// types is one of their features, so some internal code duplication in a single file is worth it.

/**
 * A [BitIndices] of the 32 bits of an [Int], holding indices `0` to `31`.
 *
 * Can be stored and queried: see [containsAll] and [containsAny] for the conditions.
 */
@Queryable
@JvmInline
public value class IntBitIndices(public val mask: Int) : BitIndices {
    public constructor() : this(0)

    override val capacity: Int get() = CAPACITY

    override val size: Int get() = mask.countOneBits()

    override fun isEmpty(): Boolean = mask == 0

    override fun get(index: Int): Boolean {
        checkIndex(index, CAPACITY)
        return (mask and (1 shl (MAX_INDEX - index))) != 0
    }

    override fun contains(element: Int): Boolean = element in 0..<CAPACITY && get(element)

    override fun containsAll(elements: Collection<Int>): Boolean {
        var wanted = 0
        for (index in elements) {
            if (index !in 0..<CAPACITY) return false
            wanted = wanted or (1 shl (MAX_INDEX - index))
        }
        return (wanted and mask) == wanted
    }

    /** Returns `true` if every index in [indices] is also in this set. */
    public fun containsAll(indices: IntBitIndices): Boolean = (indices.mask and mask) == indices.mask

    override fun iterator(): Iterator<Int> = object : Iterator<Int> {
        var unscanned = mask

        override fun hasNext(): Boolean = unscanned != 0

        override fun next(): Int {
            if (unscanned == 0) throw NoSuchElementException()
            val index = unscanned.countLeadingZeroBits()
            unscanned = unscanned xor (1 shl (MAX_INDEX - index))
            return index
        }
    }

    /** Returns the union of this set and [other]. */
    public operator fun plus(other: IntBitIndices): IntBitIndices = IntBitIndices(mask or other.mask)

    override fun toString(): String = joinToString(prefix = "IntBitIndices[", postfix = "]")

    public companion object {
        public const val CAPACITY: Int = Int.SIZE_BITS

        public const val MAX_INDEX: Int = CAPACITY - 1

        /**
         * Returns a set of the given [indices].
         *
         * @throws IndexOutOfBoundsException if an index is not in `0..MAX_INDEX`.
         */
        public fun from(indices: Iterator<Int>): IntBitIndices {
            var mask = 0
            for (index in indices) {
                checkIndex(index, CAPACITY)
                mask = mask or (1 shl (MAX_INDEX - index))
            }
            return IntBitIndices(mask)
        }
    }
}

/**
 * A [BitIndices] of the 64 bits of a [Long], holding indices `0` to `63`.
 *
 * Unlike [IntBitIndices], it can't be queried (the database has no conditions on a [Long]'s bits), and a JSON
 * client can lose precision on a 64-bit number, so prefer [IntBitIndices] for anything stored or sent.
 */
@JvmInline
public value class LongBitIndices(public val mask: Long) : BitIndices {
    public constructor() : this(0L)

    override val capacity: Int get() = CAPACITY

    override val size: Int get() = mask.countOneBits()

    override fun isEmpty(): Boolean = mask == 0L

    override fun get(index: Int): Boolean {
        checkIndex(index, CAPACITY)
        return (mask and (1L shl (MAX_INDEX - index))) != 0L
    }

    override fun contains(element: Int): Boolean = element in 0..<CAPACITY && get(element)

    override fun containsAll(elements: Collection<Int>): Boolean {
        var wanted = 0L
        for (index in elements) {
            if (index !in 0..<CAPACITY) return false
            wanted = wanted or (1L shl (MAX_INDEX - index))
        }
        return (wanted and mask) == wanted
    }

    /** Returns `true` if every index in [indices] is also in this set. */
    public fun containsAll(indices: LongBitIndices): Boolean = (indices.mask and mask) == indices.mask

    override fun iterator(): Iterator<Int> = object : Iterator<Int> {
        var unscanned = mask

        override fun hasNext(): Boolean = unscanned != 0L

        override fun next(): Int {
            if (unscanned == 0L) throw NoSuchElementException()
            val index = unscanned.countLeadingZeroBits()
            unscanned = unscanned xor (1L shl (MAX_INDEX - index))
            return index
        }
    }

    /** Returns the union of this set and [other]. */
    public operator fun plus(other: LongBitIndices): LongBitIndices = LongBitIndices(mask or other.mask)

    override fun toString(): String = joinToString(prefix = "LongBitIndices[", postfix = "]")

    public companion object {
        public const val CAPACITY: Int = Long.SIZE_BITS

        public const val MAX_INDEX: Int = CAPACITY - 1

        /**
         * Returns a set of the given [indices].
         *
         * @throws IndexOutOfBoundsException if an index is not in `0..MAX_INDEX`.
         */
        public fun from(indices: Iterator<Int>): LongBitIndices {
            var mask = 0L
            for (index in indices) {
                checkIndex(index, CAPACITY)
                mask = mask or (1L shl (MAX_INDEX - index))
            }
            return LongBitIndices(mask)
        }
    }
}

/** Returns an [IntBitIndices] of the given [indices]. */
public fun intBitIndicesOf(vararg indices: Int): IntBitIndices = IntBitIndices.from(indices.iterator())

/** Returns a [LongBitIndices] of the given [indices]. */
public fun longBitIndicesOf(vararg indices: Int): LongBitIndices = LongBitIndices.from(indices.iterator())

private fun checkIndex(index: Int, capacity: Int) {
    if (index !in 0..<capacity) throw IndexOutOfBoundsException("Index $index is out of range 0..${capacity - 1}")
}

/** Returns a condition matching a value that holds every index in [indices]. */
public fun <T> Optic<T, IntBitIndices>.containsAll(indices: IntBitIndices): Condition<T> =
    @OptIn(InlineProperty::class)
    mask.mapCondition(Condition.IntBitsSet(indices.mask))

/** Returns a condition matching a value that holds at least one index in [indices]. */
public fun <T> Optic<T, IntBitIndices>.containsAny(indices: IntBitIndices): Condition<T> =
    @OptIn(InlineProperty::class)
    mask.mapCondition(Condition.IntBitsAnySet(indices.mask))
