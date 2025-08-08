package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.serializerOrContextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder


/**
 * A specification for a path on a server, such as `/path/to/item`.
 * These path specifications have typed arguments, so in `models/{id}`, the `id` is typed to a particular primitive, such as a `String`.
 * A `PathSpec` also defines what comes after the segments, as nothing, a trailing slash, or arbitrary trailing path segments.
 */
@Serializable(DummyPathSpecSerializer::class)
public sealed class PathSpec(public val segments: List<Segment>, public val after: Afterwards) {
    override fun hashCode(): Int = segments.hashCode() * 31 + after.hashCode()
    override fun equals(other: Any?): Boolean =
        other is PathSpec && this.segments == other.segments && this.after == other.after

    override fun toString(): String = "/" + segments.joinToString("/") + when (after) {
        Afterwards.None -> ""
        Afterwards.TrailingSlash -> "/"
        Afterwards.TrailingSegments -> "/{...}"
    }

    public abstract fun path(constant: String): PathSpec
    public abstract fun <T> arg(wildcard: Segment.Wildcard<T>): PathSpec

    public abstract val wildcards: List<Segment.Wildcard<*>>

    /**
     * What comes after the URL segments.
     */
    public enum class Afterwards {
        /**
         * Nothing - no trailing slash.
         */
        None,

        /**
         * A trailing slash after the segments.
         */
        TrailingSlash,

        /**
         * An arbitrary number of segments with or without a trailing segment afterwards.
         */
        TrailingSegments;

        public companion object {
            public fun fromString(string: String): Afterwards = when {
                string.endsWith("/{...}") -> TrailingSegments
                string.endsWith("/") -> TrailingSlash
                else -> None
            }
        }
    }

    /**
     * A single segment in a path.
     * For example, `thing` in the path `path/to/thing/here`.
     */
    public sealed class Segment {

        /**
         * Any value can be present in this path segment, as long as it can be parsed using the given [serializer].
         */
        public data class Wildcard<T>(val name: String, val serializer: KSerializer<T>) : Segment() {
            override fun toString(): String = "{$name}"
        }

        /**
         * A constant string in the path must be present.
         */
        public data class Constant(val value: String) : Segment() {
            init {
                require(!value.contains('/')) { "Path constant cannot contain a slash" }
            }

            override fun toString(): String = value
        }

        public companion object {
            /**
             * String format:
             * constant/{argument}/something/{...}
             * All arguments will be of type [String].
             */
            public fun fromString(string: String): List<Segment> {
                return string.split('/')
                    .filter { it.isNotBlank() }
                    .filter { it != "{...}" }
                    .map {
                        if (it.startsWith("{"))
                            Wildcard(it.removePrefix("{").removeSuffix("}"), String.serializer())
                        else
                            Constant(it)
                    }
            }
        }
    }

    public companion object {
        public val root: PathSpec0 = PathSpec0(emptyList(), Afterwards.None)
    }
}

public object DummyPathSpecSerializer : KSerializer<PathSpec> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("PathSpec", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): PathSpec =
        PathSpec0(decoder.decodeString().split('/').map { PathSpec.Segment.Constant(it) }, PathSpec.Afterwards.None)

    override fun serialize(encoder: Encoder, value: PathSpec) {
        encoder.encodeString(value.segments.joinToString("/") { it.toString() })
    }
}

/**
 * A [PathSpec] with no arguments - in other words, all segments are constant values.
 */
public class PathSpec0(segments: List<Segment>, after: Afterwards) : PathSpec(segments, after) {
    public constructor(vararg constants: String):this(constants.map { Segment.Constant(it) }, Afterwards.None)
    override val wildcards: List<Segment.Wildcard<*>> get() = listOf()
    public val slash: PathSpec0 get() = PathSpec0(segments, Afterwards.TrailingSlash)
    public val any: PathSpec0 get() = PathSpec0(segments, Afterwards.TrailingSegments)

    public override fun path(constant: String): PathSpec0 = PathSpec0(segments + Segment.Constant(constant), Afterwards.None)
    public override fun <T> arg(wildcard: Segment.Wildcard<T>): PathSpec1<T> = PathSpec1(segments + wildcard, after, wildcard)

    public inline fun <reified T> arg(name: String): PathSpec1<T> = arg(Segment.Wildcard(name, serializerOrContextual<T>()))
}

/**
 * A [PathSpec] with a single typed argument.
 */
public class PathSpec1<A>(
    segments: List<Segment>,
    after: Afterwards,
    public val first: Segment.Wildcard<A>
) : PathSpec(segments, after) {
    override val wildcards: List<Segment.Wildcard<*>> = listOf(first)
    public val slash: PathSpec1<A> get() = PathSpec1(segments, Afterwards.TrailingSlash, first)
    public val any: PathSpec1<A> get() = PathSpec1(segments, Afterwards.TrailingSegments, first)

    public override fun path(constant: String): PathSpec1<A> = PathSpec1<A>(segments + Segment.Constant(constant), Afterwards.None, first)
    public override fun <T> arg(wildcard: Segment.Wildcard<T>): PathSpec2<A, T> = PathSpec2<A, T>(segments + wildcard, after, first, wildcard)

    public inline fun <reified T> arg(name: String): PathSpec2<A, T> = arg(Segment.Wildcard(name, serializerOrContextual<T>()))
}

/**
 * A [PathSpec] with two typed arguments.
 */
public class PathSpec2<A, B>(
    segments: List<Segment>,
    after: Afterwards,
    public val first: Segment.Wildcard<A>,
    public val second: Segment.Wildcard<B>
) : PathSpec(segments, after) {
    override val wildcards: List<Segment.Wildcard<*>> = listOf(first, second)
    public val slash: PathSpec2<A, B> get() = PathSpec2(segments, Afterwards.TrailingSlash, first, second)
    public val any: PathSpec2<A, B> get() = PathSpec2(segments, Afterwards.TrailingSegments, first, second)

    public override fun path(constant: String): PathSpec2<A, B> = PathSpec2<A, B>(segments + Segment.Constant(constant), Afterwards.None, first, second)
    public override fun <T> arg(wildcard: Segment.Wildcard<T>): PathSpec3<A, B, T> = PathSpec3<A, B, T>(segments + wildcard, after, first, second, wildcard)

    public inline fun <reified T> arg(name: String): PathSpec3<A, B, T> = arg(Segment.Wildcard(name, serializerOrContextual<T>()))
}

/**
 * A [PathSpec] with three typed arguments.
 */
public class PathSpec3<A, B, C>(
    segments: List<Segment>,
    after: Afterwards,
    public val first: Segment.Wildcard<A>,
    public val second: Segment.Wildcard<B>,
    public val third: Segment.Wildcard<C>
) : PathSpec(segments, after) {
    override val wildcards: List<Segment.Wildcard<*>> = listOf(first, second, third)
    public val slash: PathSpec3<A, B, C> get() = PathSpec3(segments, Afterwards.TrailingSlash, first, second, third)
    public val any: PathSpec3<A, B, C> get() = PathSpec3(segments, Afterwards.TrailingSegments, first, second, third)

    public override fun path(constant: String): PathSpec3<A, B, C> = PathSpec3<A, B, C>(segments + Segment.Constant(constant), Afterwards.None, first, second, third)
    public override fun <T> arg(wildcard: Segment.Wildcard<T>): PathSpecMany = PathSpecMany(segments + wildcard, after, wildcards + wildcard)

    public inline fun <reified T> arg(name: String): PathSpecMany = arg(Segment.Wildcard(name, serializerOrContextual<T>()))
}

/**
 * A [PathSpec] with three typed arguments.
 */
public class PathSpecMany(
    segments: List<Segment>,
    after: Afterwards,
    override val wildcards: List<Segment.Wildcard<*>>
) : PathSpec(segments, after) {
    public val slash: PathSpecMany get() = PathSpecMany(segments, Afterwards.TrailingSlash, wildcards)
    public val any: PathSpecMany get() = PathSpecMany(segments, Afterwards.TrailingSegments, wildcards)
    public override fun path(constant: String): PathSpecMany = PathSpecMany(segments + Segment.Constant(constant), Afterwards.None, wildcards)
    public override fun <T> arg(wildcard: Segment.Wildcard<T>): PathSpecMany = PathSpecMany(segments + wildcard, after, wildcards + wildcard)
}
