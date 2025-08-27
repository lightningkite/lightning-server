package com.lightningkite.lightningserver.pathing

import com.lightningkite.services.data.StringArrayFormat
import com.lightningkite.lightningserver.pathing.PathSpec.Afterwards
import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.EmptySerializersModule

/**
 * A pattern for a [PathSpec] or a [ConcretePath] to match against. Both [PathSpec]
 * and [ConcretePath] can be converted into a [PathPredicate] using the `toPredicate()`
 * method.
 *
 * ## Matching
 *
 * A [PathPredicate] created by `PathSpec.toPredicate()` will match:
 *  - The original PathSpec it was derived from
 *  - Any equivalent PathSpec with the same structure
 *  - Any ConcretePath that can be derived from the original PathSpec
 *  - If the original PathSpec ends with `/{...}` (trailing wildcard segments),
 *    it will also match paths with additional segments beyond the original structure
 *
 * A [PathPredicate] created by `ConcretePath.toPredicate()` will match only equivalent
 * concrete paths with the same arguments.
 *
 * ## Structure
 *
 * A [PathPredicate] is built out of constant segments and wildcard segments, much
 * like a [PathSpec]. A wildcard will accept any *argument* in its relative path position,
 * and will accept other wildcards of the same name. Note that it only accepts *arguments*,
 * constant path segments in a [PathSpec] will not be accepted by a wildcard.
 *
 * A constant predicate segment will accept only other constants in its relative position with the same
 * value. A fulfilled wildcard value in a [ConcretePath] is treated as a constant segment with it's
 * value being the provided argument.
 *
 * ## Examples
 *
 * - `/path/to/item` matches `/path/to/item`
 * - `/path/to/item` !matches `/path/to/item/`
 *
 *
 * - `/path/to/{id}` matches `/path/to/{id}` and `/path/to/item`
 * - `/path/to/item` !matches `/path/to/{id}`
 * - `/path/to/{id}` !matches `/path/to/{otherName}`
 *
 *
 * - `/path/to/{...}` matches `/path/to` and `path/to/` and `/path/to/item` and `/path/to/item/`
 *
 *
 * - `/path/to/{id}/then/{item}` matches `/path/to/1234/then/{item}`
 * - `/path/to/1234/then/{item}` !matches `/path/to/{id}/then/{item}`
 * */
@Serializable(PathPredicateSerializer::class)
public class PathPredicate private constructor(
    private val segments: Array<Segment>,
    public val after: Afterwards
) {
    public constructor(path: PathSpec) : this(path.segments.map(::Segment).toTypedArray(), path.after)

    public constructor(path: ConcretePath<*>, format: StringArrayFormat) : this(
        path.segments.map { Segment(it, format) }.toTypedArray() + (path.wildcard?.segments?.map(::Segment) ?: emptyList()),
        after = if (path.wildcard?.trailingSlash == true) Afterwards.TrailingSlash else Afterwards.None
    )

    override fun equals(other: Any?): Boolean =
        other is PathPredicate && segments.contentEquals(other.segments) && after == other.after

    override fun hashCode(): Int =
        segments.contentHashCode() * 31 + after.hashCode()

    public companion object {
        public fun fromString(string: String): PathPredicate = PathPredicate(
            string.splitToSequence('/')
                .filter { it.isNotBlank() }
                .filter { it != "{...}" }
                .map(::Segment)
                .toList()
                .toTypedArray(),
            Afterwards.fromString(string)
        )
    }

    private data class Segment(val raw: String) {
        constructor(segment: PathSpec.Segment) : this(segment.toString())
        constructor(segment: ConcretePath.Segment, format: StringArrayFormat) : this(segment.toString(format))

        val wildcard: Boolean = raw.startsWith('{')
        val value: String = if (wildcard) raw.substringAfter('{').substringBefore('}') else raw

        fun matches(segment: PathSpec.Segment?): Boolean = when (segment) {
            null -> false
            is PathSpec.Segment.Constant -> !wildcard && segment.value == value
            is PathSpec.Segment.Wildcard<*> -> wildcard && segment.name == value
        }
        fun matches(segment: ConcretePath.Segment?, format: StringArrayFormat): Boolean = when (segment) {
            null -> false
            is ConcretePath.Segment.Constant -> wildcard || segment.value == value
            is ConcretePath.Segment.WildcardWithValue<*> -> wildcard || segment.toString(format) == value
        }

        override fun toString(): String = raw
    }

    override fun toString(): String = segments.joinToString(
        separator = "/",
        prefix = "/",
        postfix = when (after) {
            Afterwards.None -> ""
            Afterwards.TrailingSlash -> "/"
            Afterwards.TrailingSegments -> "/{...}"
        }
    )

    public fun matches(path: PathSpec): Boolean {
        for ((idx, segment) in segments.withIndex()) {
            if (!segment.matches(path.segments.getOrNull(idx))) return false
        }
        return if (after == Afterwards.TrailingSegments) true
        else segments.size == path.segments.size && after == path.after
    }

    context(server: ServerRuntime)
    public fun matches(path: ConcretePath<*>): Boolean {
        for ((idx, segment) in segments.withIndex()) {
            if (!segment.matches(path.segments.getOrNull(idx), server.internalSerialization.stringArrayFormat)) return false
        }
        return when (after) {
            Afterwards.None -> segments.size == path.segments.size && !path.hasTrailingSlash
            Afterwards.TrailingSlash -> segments.size == path.segments.size && path.hasTrailingSlash
            Afterwards.TrailingSegments -> true
        }
    }

    public operator fun invoke(on: PathSpec): Boolean = matches(on)
    context(_: ServerRuntime) public operator fun invoke(on: ConcretePath<*>): Boolean = matches(on)
}

public fun PathSpec.toPredicate(): PathPredicate = PathPredicate(this)

context(server: ServerRuntime)
public fun ConcretePath<*>.toPredicate(): PathPredicate = PathPredicate(this, server.internalSerialization.stringArrayFormat)

public object PathPredicateSerializer : KSerializer<PathPredicate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.lightningserver.PathPredicate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: PathPredicate) { encoder.encodeString(value.toString()) }
    override fun deserialize(decoder: Decoder): PathPredicate = PathPredicate.fromString(decoder.decodeString())
}