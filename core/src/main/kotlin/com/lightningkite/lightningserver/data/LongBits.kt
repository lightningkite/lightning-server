package com.lightningkite.lightningserver.data

/**
 * A compact bit set stored in a single Long value, supporting indices 0-63.
 *
 * Each bit position represents whether that index is present in the set.
 * Useful for efficiently storing small sets of integers, particularly for cron patterns.
 *
 * Example:
 * ```kotlin
 * val bits = LongBits(listOf(0, 5, 10, 15))
 * println(0 in bits)  // true
 * println(1 in bits)  // false
 * println(bits.toString())  // "0,5,10,15"
 * ```
 *
 * @property long The underlying bit representation
 */
@JvmInline
public value class LongBits(public val long: Long) : Iterable<Int> {
    /**
     * Creates a LongBits from an iterable of indices.
     *
     * @param iterable Indices to set (0-63). Values outside this range are ignored.
     */
    public constructor(iterable: Iterable<Int>) : this(
        iterable.fold(0x0L) { acc, index ->
            acc or (1L shl index)
        }
    )

    /**
     * Combines this bit set with another using bitwise OR.
     *
     * @param other Another bit set to combine with
     * @return A new LongBits containing all bits from both sets
     */
    public operator fun plus(other: LongBits): LongBits = LongBits(long or other.long)

    /**
     * Finds the lowest set bit at or after the given index.
     *
     * @param index The starting index to search from (inclusive)
     * @return The index of the lowest set bit >= index, or -1 if none found
     */
    public fun lowestIncluding(index: Int): Int {
        var result = index
        var current = long ushr index
        while (current != 0L) {
            if (current and 0x1 != 0L) return result
            result++
            current = current ushr 1
        }
        return -1
    }

    /**
     * Finds the lowest set bit strictly after the given index.
     *
     * Equivalent to `lowestIncluding(index + 1)`.
     *
     * @param index The index after which to search
     * @return The index of the lowest set bit > index, or -1 if none found
     */
    public fun lowestAfter(index: Int): Int = lowestIncluding(index + 1)

    /**
     * Returns an iterator over all set bit indices in ascending order.
     */
    override fun iterator(): Iterator<Int> {
        return object : Iterator<Int> {
            var index = 0
            var num = long

            override fun hasNext(): Boolean {
                return num != 0L
            }

            override fun next(): Int {
                while (num != 0L) {
                    if (num % 2L == 1L) {
                        num = num shr 1
                        return index++
                    }
                    num = num shr 1
                    index++
                }
                return -1
            }
        }
    }

    /**
     * Checks if the given index's bit is set.
     *
     * @param index The bit index to check (0-63)
     * @return true if the bit is set, false otherwise
     */
    public operator fun contains(index: Int): Boolean = (long and (1L shl index)) != 0L

    /**
     * Returns a compact string representation using ranges where possible.
     *
     * Examples:
     * - "0,5,10" for discrete values
     * - "0-5,10-15" for consecutive ranges
     * - "0-2,5,10-12" for mixed ranges and discrete values
     */
    override fun toString(): String = buildString {
        var wasOn = false
        var start = -1
        var needsComma = false
        for (i in 0..<64) {
            val on = contains(i)
            if (on && !wasOn) {
                start = i
            } else if (!on && wasOn) {
                if (start == i - 1) {
                    if (needsComma) append(',') else needsComma = true
                    append(start)
                } else {
                    if (needsComma) append(',') else needsComma = true
                    append(start)
                    append('-')
                    append(i - 1)
                }
            }
            wasOn = on
        }
    }

    public val size: Int get() = long.countOneBits()
    public fun minus(other: LongBits): LongBits = LongBits(long and other.long.inv())
    public fun intersect(other: LongBits): LongBits = LongBits(long and other.long)
    public fun isEmpty(): Boolean = long == 0L
}

/*
 * TODO: API Recommendations for LongBits.kt
 *
 * 4. Consider adding a first() extension that throws if empty (similar to Iterable.first()):
 *    - Currently lowestIncluding(0) returns -1 if empty
 *
 * 5. Add a companion object parse() method to parse from string format:
 *    - LongBits.parse("0,5,10-15") for creating from string representation
 */