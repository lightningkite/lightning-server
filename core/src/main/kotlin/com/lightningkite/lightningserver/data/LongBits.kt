package com.lightningkite.lightningserver.data

@JvmInline
public value class LongBits(public val long: Long) : Iterable<Int> {
    public constructor(iterable: Iterable<Int>) : this(
        iterable.fold(0x0L) { acc, index ->
            acc or (1L shl index)
        }
    )

    public operator fun plus(other: LongBits): LongBits = LongBits(long or other.long)

    public fun lowestIncluding(index: Int): Int {
        var result = index
        var current = long ushr index
        while (current != 0L) {
            if (current % 2L == 1L) return result
            result++
            current = current ushr 1
        }
        return -1
    }

    public fun lowestAfter(index: Int): Int = lowestIncluding(index + 1)

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

    public operator fun contains(index: Int): Boolean = (long and (1L shl index)) > 0L

    override fun toString(): String = buildString {
        var wasOn = false
        var start = -1
        var needsComma = false
        for (i in 0..64) {
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
}