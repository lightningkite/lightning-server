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
public data class FieldIdentifierSet(
    @SerialName("f0") public val fields0: Segment,
    @SerialName("f1") public val fields1: Segment,
) : Set<ModelFieldId> {
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

    public operator fun get(id: ModelFieldId): Boolean = segment(id.raw / Segment.CAPACITY)[id.raw % Segment.CAPACITY]

    override fun contains(element: ModelFieldId): Boolean = get(element)

    public fun containsAll(indices: FieldIdentifierSet): Boolean {
        for (i in 0..<SEGMENTS) {
            if (!segment(i).containsAll(indices.segment(i))) return false
        }
        return true
    }

    override fun containsAll(elements: Collection<ModelFieldId>): Boolean = containsAll(FieldIdentifierSet.from(elements))

    override fun iterator(): Iterator<ModelFieldId> = object : Iterator<ModelFieldId> {
        var s = 0
        var iter = segment(s).iterator()

        override fun hasNext(): Boolean {
            while (!iter.hasNext() && s < SEGMENTS - 1) iter = segment(++s).iterator()
            return iter.hasNext()
        }

        override fun next(): ModelFieldId {
            if (!hasNext()) throw NoSuchElementException()
            return ModelFieldId(iter.next() + s * Segment.CAPACITY)
        }
    }

    /** Union of two sets. */
    public operator fun plus(other: FieldIdentifierSet): FieldIdentifierSet =
        FieldIdentifierSet(fields0 + other.fields0, fields1 + other.fields1)

    public operator fun plus(id: ModelFieldId): FieldIdentifierSet = this + from(listOf(id))

    public companion object {
        public const val SEGMENTS: Int = 2

        public const val CAPACITY: Int = SEGMENTS * Segment.CAPACITY

        public val EMPTY: FieldIdentifierSet = FieldIdentifierSet(Segment(0), Segment(0))

        internal val segmentProperties: Array<SerializableProperty<FieldIdentifierSet, Segment>> = arrayOf(fields0, fields1)

        /**
         * Creates a [FieldIdentifierSet] set from a sequence of ids.
         * */
        public fun from(ids: Iterable<ModelFieldId>): FieldIdentifierSet {
            val segments = IntArray(SEGMENTS)
            for (id in ids) {
                val s = id.raw / Segment.CAPACITY
                val i = id.raw % Segment.CAPACITY
                segments[s] = segments[s] or (1 shl (Segment.MAX_INDEX - i))
            }
            return FieldIdentifierSet(
                Segment(segments[0]),
                Segment(segments[1])
            )
        }
    }
}

private inline fun FieldIdentifierSet.mapSegmentConditions(transform: (Int) -> Condition<Int>): Array<Condition<FieldIdentifierSet>?> {
    return Array(FieldIdentifierSet.SEGMENTS) { sIdx ->
        val segment = segment(sIdx)
        if (segment.isEmpty()) return@Array null
        val property = FieldIdentifierSet.segmentProperties[sIdx]
        Condition.OnField(
            property,
            Condition.OnField(
                @OptIn(InlineProperty::class)
                FieldIdentifierSet.Segment.mask,
                transform(segment.mask)
            )
        )
    }
}

public fun <T> DataClassPath<T, FieldIdentifierSet>.containsAny(ids: FieldIdentifierSet): Condition<T> =
    mapCondition(
        Condition.orNotNull(
            *ids.mapSegmentConditions(Condition<Int>::IntBitsAnySet)
        )
    )

public fun <T> DataClassPath<T, FieldIdentifierSet>.containsAll(ids: FieldIdentifierSet): Condition<T> =
    mapCondition(
        Condition.andNotNull(
            *ids.mapSegmentConditions(Condition<Int>::IntBitsSet)
        )
    )