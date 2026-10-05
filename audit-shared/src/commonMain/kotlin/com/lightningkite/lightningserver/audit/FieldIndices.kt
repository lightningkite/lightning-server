package com.lightningkite.lightningserver.audit

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.DataClassPath
import com.lightningkite.services.database.InlineProperty
import com.lightningkite.services.database.SerializableProperty
import com.lightningkite.services.database.andNotNull
import com.lightningkite.services.database.orNotNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@GenerateDataClassPaths
public data class FieldIndices(
    @SerialName("f0") public val fields0: Segment,
    @SerialName("f1") public val fields1: Segment,
) : Set<Int> {
    public typealias Segment = BitIndices

    public fun segment(index: Int): Segment = when (index) {
        0 -> fields0
        1 -> fields1
        else -> throw IndexOutOfBoundsException("Segment $index does not exist; there are $SEGMENTS segments.")
    }

    public fun segments(): List<Segment> = List(SEGMENTS, ::segment)

    private inline fun onSegments(action: (Segment) -> Unit) {
        for (i in 0..<SEGMENTS) action(segment(i))
    }

    override val size: Int
        get() {
            var total = 0
            onSegments { total += it.size }
            return total
        }

    override fun isEmpty(): Boolean {
        onSegments { if (it.isNotEmpty()) return false }
        return true
    }

    public operator fun get(index: Int): Boolean {
        ensureValidIndex(index)
        return segment(index / Segment.CAPACITY)[index % Segment.CAPACITY]
    }

    override fun contains(element: Int): Boolean = get(element)

    public fun containsAll(indices: FieldIndices): Boolean {
        for (i in 0..<SEGMENTS) {
            if (!segment(i).containsAll(indices.segment(i))) return false
        }
        return true
    }

    override fun containsAll(elements: Collection<Int>): Boolean = containsAll(FieldIndices.from(elements.iterator()))

    override fun iterator(): Iterator<Int> = object : Iterator<Int> {
        var s = 0
        var iter = segment(s).iterator()

        override fun next(): Int {
            if (!iter.hasNext()) {
                if (s >= SEGMENTS - 1) throw NoSuchElementException()
                s += 1
                iter = segment(s).iterator()
            }
            return iter.next() + (s * Segment.MAX_INDEX)
        }

        override fun hasNext(): Boolean = iter.hasNext() || s < SEGMENTS - 1
    }

    /** Union of two sets. */
    public operator fun plus(other: FieldIndices): FieldIndices =
        FieldIndices(fields0 + other.fields0, fields1 + other.fields1)

    public companion object {
        public const val SEGMENTS: Int = 2

        public const val CAPACITY: Int = SEGMENTS * Segment.CAPACITY

        internal val segmentProperties: Array<SerializableProperty<FieldIndices, Segment>> = arrayOf(fields0, fields1)

        /**
         * Creates a [FieldIndices] set from a sequence of indices.
         * */
        public fun from(indices: Iterator<Int>): FieldIndices {
            val segments = IntArray(SEGMENTS)
            for (idx in indices) {
                ensureValidIndex(idx)
                val s = idx / Segment.CAPACITY
                val i = idx % Segment.CAPACITY
                segments[s] = segments[s] or (1 shl (Segment.MAX_INDEX - i))
            }
            return FieldIndices(
                Segment(segments[0]),
                Segment(segments[1])
            )
        }

        internal fun fromSegments(fields0: Segment, fields1: Segment): FieldIndices = FieldIndices(fields0, fields1)
    }
}

public fun fieldIndicesOf(vararg indices: Int): FieldIndices = FieldIndices.from(indices.iterator())

private fun ensureValidIndex(index: Int) {
    if (index !in 0..<FieldIndices.CAPACITY) throw IndexOutOfBoundsException("Index $index is out of bounds for range 0..${FieldIndices.CAPACITY-1}.")
}

private inline fun FieldIndices.mapSegmentConditions(transform: (Int) -> Condition<Int>): Array<Condition<FieldIndices>?> {
    return Array(FieldIndices.SEGMENTS) { sIdx ->
        val segment = segment(sIdx)
        if (segment.isEmpty()) return@Array null
        val property = FieldIndices.segmentProperties[sIdx]
        Condition.OnField(
            property,
            Condition.OnField(
                @OptIn(InlineProperty::class)
                FieldIndices.Segment.mask,
                transform(segment.mask)
            )
        )
    }
}

public fun <T> DataClassPath<T, FieldIndices>.containsAny(indices: FieldIndices): Condition<T> =
    mapCondition(
        Condition.orNotNull(
            *indices.mapSegmentConditions(Condition<Int>::IntBitsAnySet)
        )
    )

public fun <T> DataClassPath<T, FieldIndices>.containsAll(indices: FieldIndices): Condition<T> =
    mapCondition(
        Condition.andNotNull(
            *indices.mapSegmentConditions(Condition<Int>::IntBitsSet)
        )
    )