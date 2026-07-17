package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.serialization.serializerOrContextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder


/**
 * A specification for a path on a server, such as `/path/to/item`.
 *
 * PathSpec is the foundation of Lightning Server's type-safe routing system. It represents a URL path pattern
 * with typed path arguments (wildcards) and defines matching behavior.
 *
 * For example, in `/users/{id}/posts/{postId}`:
 * - `users` and `posts` are constant segments
 * - `{id}` and `{postId}` are typed wildcard segments (e.g., String, Int, UUID)
 *
 * The type system ensures path arguments are properly typed throughout your application. When a request
 * matches a PathSpec, the wildcard values are parsed and made available through type-safe accessors
 * (`.arg1`, `.arg2`, `.arg3`).
 *
 * PathSpec also supports trailing segments (`/{...}`) for catch-all patterns.
 *
 * Example usage:
 * ```kotlin
 * // Define a path: GET /users/{userId}/posts/{postId}
 * val userPost = path.path("users").arg<String>("userId")
 *     .path("posts").arg<Int>("postId")
 *
 * // In a handler, access the typed arguments:
 * userPost.get bind HttpHandler { request ->
 *     val userId: String = request.path.arg1
 *     val postId: Int = request.path.arg2
 *     // ...
 * }
 * ```
 *
 * @property segments The list of path segments (constants and wildcards)
 * @property after What comes after the segments ([Afterwards.None] or [Afterwards.TrailingSegments])
 * @see PathSpec0 For paths with no wildcards
 * @see PathSpec1 For paths with one typed wildcard
 * @see PathSpec2 For paths with two typed wildcards
 * @see PathSpec3 For paths with three typed wildcards
 * @see PathSpecMany For paths with four or more wildcards
 */
@Serializable(DummyPathSpecSerializer::class)
public sealed class PathSpec protected constructor(public val segments: List<Segment>, public val after: Afterwards) {
    override fun hashCode(): Int = segments.hashCode() * 31 + after.hashCode()
    override fun equals(other: Any?): Boolean =
        other is PathSpec && this.segments == other.segments && this.after == other.after

    override fun toString(): String = if (segments.isEmpty()) "/" else "/" + segments.joinToString("/") + when (after) {
        Afterwards.None -> ""
        Afterwards.TrailingSegments -> "/{...}"
    }

    /**
     * Appends a constant string segment to this path specification.
     *
     * @param constant The constant string value to add to the path
     * @return A new PathSpec with the appended segment
     */
    public abstract fun path(constant: String): PathSpec

    /**
     * Appends a typed wildcard segment to this path specification.
     *
     * The wildcard will match any value that can be deserialized using the provided serializer.
     *
     * @param wildcard The wildcard specification including name and serializer
     * @return A new PathSpec with the appended wildcard
     */
    public abstract fun <T> arg(wildcard: Segment.Wildcard<T>): PathSpec

    /**
     * The list of all wildcard segments in this path specification, in order.
     *
     * These correspond to the typed arguments that will be extracted when matching a request.
     */
    public abstract val wildcards: List<Segment.Wildcard<*>>

    /**
     * The parent path specification, or null if this is the root path.
     *
     * For example, the parent of `/users/{id}/posts` is `/users/{id}`.
     */
    public val parent: PathSpec? get() = if (segments.isEmpty()) null else PathSpec.make(segments.dropLast(1), after)

    /**
     * What comes after the URL segments.
     */
    public enum class Afterwards {
        /**
         * Nothing - no trailing slash.
         */
        None,

        /**
         * An arbitrary number of segments with or without a trailing segment afterwards.
         */
        TrailingSegments;

        public companion object {
            public fun fromString(string: String): Afterwards = when {
                string.endsWith("/{...}") -> TrailingSegments
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
            public val Empty: Constant = Constant("")

            /**
             * String format:
             * /constant/{argument}/something/{...}
             * All arguments will be of type [String].
             */
            public fun fromString(string: String): List<Segment> {
                return string.removePrefix("/").split('/')
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
        internal fun make(segments: List<PathSpec.Segment>, after: PathSpec.Afterwards): PathSpec {
            val wildcards = segments.filterIsInstance<PathSpec.Segment.Wildcard<*>>()
            return when (wildcards.size) {
                0 -> PathSpec0(segments, after)
                1 -> PathSpec1(segments, after, wildcards[0])
                2 -> PathSpec2(segments, after, wildcards[0], wildcards[1])
                3 -> PathSpec3(segments, after, wildcards[0], wildcards[1], wildcards[2])
                else -> PathSpecMany(segments, after, wildcards)
            }
        }
    }
}

public infix fun PathSpec.commonPrefix(other: PathSpec): PathSpec {
    val common = ArrayList<PathSpec.Segment>()
    var i = 0
    while (i < segments.size && i < other.segments.size) {
        if (segments[i] == other.segments[i]) {
            common.add(segments[i])
            i++
        } else break
    }
    return PathSpec.make(common, PathSpec.Afterwards.fromString(this.toString().commonPrefixWith(other.toString())))
}


public object DummyPathSpecSerializer : KSerializer<PathSpec> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.lightningserver.pathing.PathSpec", PrimitiveKind.STRING)
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
    public constructor(vararg constants: String) : this(constants.map { Segment.Constant(it) }, Afterwards.None)

    override val wildcards: List<Segment.Wildcard<*>> get() = listOf()
    public val slash: PathSpec0 get() = PathSpec0(segments + Segment.Empty, Afterwards.None)
    public val any: PathSpec0 get() = PathSpec0(segments, Afterwards.TrailingSegments)

    public override fun path(constant: String): PathSpec0 =
        PathSpec0(segments + Segment.Constant(constant), Afterwards.None)

    public override fun <T> arg(wildcard: Segment.Wildcard<T>): PathSpec1<T> =
        PathSpec1(segments + wildcard, after, wildcard)

    public fun resolved(trailingWildcard: PathSegments? = null): ResolvedPath<PathSpec0> =
        ResolvedPath(this, trailingWildcard)

    public inline fun <reified T> arg(name: String): PathSpec1<T> =
        arg(Segment.Wildcard(name, serializerOrContextual<T>()))

    public companion object {
        public fun fromString(string: String): PathSpec0 =
            PathSpec0(string.removePrefix("/").split('/').map { Segment.Constant(it) }, Afterwards.fromString(string))
    }
}

/**
 * A [PathSpec] with a single typed argument.
 */
public class PathSpec1<A>(
    segments: List<Segment>,
    after: Afterwards,
    public val first: Segment.Wildcard<A>,
) : PathSpec(segments, after) {
    override val wildcards: List<Segment.Wildcard<*>> = listOf(first)
    public val slash: PathSpec1<A> get() = PathSpec1(segments + Segment.Empty, Afterwards.None, first)
    public val any: PathSpec1<A> get() = PathSpec1(segments, Afterwards.TrailingSegments, first)

    public override fun path(constant: String): PathSpec1<A> =
        PathSpec1<A>(segments + Segment.Constant(constant), Afterwards.None, first)

    public override fun <T> arg(wildcard: Segment.Wildcard<T>): PathSpec2<A, T> =
        PathSpec2<A, T>(segments + wildcard, after, first, wildcard)

    public fun resolved(first: A, trailingWildcard: PathSegments? = null): ResolvedPath<PathSpec1<A>> =
        ResolvedPath(this, first, trailingWildcard)

    public inline fun <reified T> arg(name: String): PathSpec2<A, T> =
        arg(Segment.Wildcard(name, serializerOrContextual<T>()))
}

/**
 * A [PathSpec] with two typed arguments.
 */
public class PathSpec2<A, B>(
    segments: List<Segment>,
    after: Afterwards,
    public val first: Segment.Wildcard<A>,
    public val second: Segment.Wildcard<B>,
) : PathSpec(segments, after) {
    override val wildcards: List<Segment.Wildcard<*>> = listOf(first, second)
    public val slash: PathSpec2<A, B> get() = PathSpec2(segments + Segment.Empty, Afterwards.None, first, second)
    public val any: PathSpec2<A, B> get() = PathSpec2(segments, Afterwards.TrailingSegments, first, second)

    public override fun path(constant: String): PathSpec2<A, B> =
        PathSpec2<A, B>(segments + Segment.Constant(constant), Afterwards.None, first, second)

    public override fun <T> arg(wildcard: Segment.Wildcard<T>): PathSpec3<A, B, T> =
        PathSpec3<A, B, T>(segments + wildcard, after, first, second, wildcard)

    public fun resolved(
        first: A,
        second: B,
        trailingWildcard: PathSegments? = null,
    ): ResolvedPath<PathSpec2<A, B>> = ResolvedPath(this, first, second, trailingWildcard)

    public inline fun <reified T> arg(name: String): PathSpec3<A, B, T> =
        arg(Segment.Wildcard(name, serializerOrContextual<T>()))
}

/**
 * A [PathSpec] with three typed arguments.
 */
public class PathSpec3<A, B, C>(
    segments: List<Segment>,
    after: Afterwards,
    public val first: Segment.Wildcard<A>,
    public val second: Segment.Wildcard<B>,
    public val third: Segment.Wildcard<C>,
) : PathSpec(segments, after) {
    override val wildcards: List<Segment.Wildcard<*>> = listOf(first, second, third)
    public val slash: PathSpec3<A, B, C>
        get() = PathSpec3(
            segments + Segment.Empty,
            Afterwards.None,
            first,
            second,
            third
        )
    public val any: PathSpec3<A, B, C> get() = PathSpec3(segments, Afterwards.TrailingSegments, first, second, third)

    public override fun path(constant: String): PathSpec3<A, B, C> =
        PathSpec3<A, B, C>(segments + Segment.Constant(constant), Afterwards.None, first, second, third)

    public override fun <T> arg(wildcard: Segment.Wildcard<T>): PathSpecMany =
        PathSpecMany(segments + wildcard, after, wildcards + wildcard)

    public fun resolved(
        first: A,
        second: B,
        third: C,
        trailingWildcard: PathSegments? = null,
    ): ResolvedPath<PathSpec3<A, B, C>> = ResolvedPath(this, first, second, third, trailingWildcard)

    public inline fun <reified T> arg(name: String): PathSpecMany =
        arg(Segment.Wildcard(name, serializerOrContextual<T>()))
}

/**
 * A [PathSpec] with more than three typed arguments.
 */
public class PathSpecMany(
    segments: List<Segment>,
    after: Afterwards,
    override val wildcards: List<Segment.Wildcard<*>>,
) : PathSpec(segments, after) {
    public val slash: PathSpecMany get() = PathSpecMany(segments + Segment.Empty, Afterwards.None, wildcards)
    public val any: PathSpecMany get() = PathSpecMany(segments, Afterwards.TrailingSegments, wildcards)

    public override fun path(constant: String): PathSpecMany =
        PathSpecMany(segments + Segment.Constant(constant), Afterwards.None, wildcards)

    public override fun <T> arg(wildcard: Segment.Wildcard<T>): PathSpecMany =
        PathSpecMany(segments + wildcard, after, wildcards + wildcard)
}

/*
 * TODO: API Recommendations for PathSpec.kt
 *
 * 1. PathSpec equality check uses segment equality, but Wildcard<T> is a data class that includes
 *    the serializer. Two wildcards with different serializers for the same type would be unequal.
 *    Document this behavior or consider only comparing names for wildcards in path equality.
 *
 * 2. Segment.Constant has validation (no slashes) but no validation for other problematic characters
 *    like '?', '#', or '%'. Consider adding more comprehensive validation.
 *
 * 3. The Segment.fromString function always creates String-typed wildcards. This loses type information
 *    and could lead to runtime deserialization issues. Document this limitation clearly.
 *
 * 4. PathSpec.Afterwards.fromString uses simple string matching. A path like "/test/{arg}" would
 *    incorrectly detect TrailingSegments if arg name happens to be "...}". Use more robust parsing.
 *
 * 5. The DummyPathSpecSerializer is marked as "Dummy" suggesting it's incomplete or for testing only.
 *    Either implement properly or document why serialization is limited.
 *
 * 6. No validation that wildcard names are unique within a path. Having two wildcards with the same
 *    name could cause confusion. Consider adding validation in the make() factory.
 *
 * 7. The PathSpec classes (PathSpec0-3, PathSpecMany) have significant code duplication.
 *    Consider using inline classes or sealed interfaces to reduce duplication.
 *
 * 8. No way to check if two PathSpecs are ambiguous/overlapping. For example, /users/{id} and /users/admin
 *    could match the same request. Consider adding a PathSpec.overlapsWith(other: PathSpec) method.
 *
 * 9. The make() factory uses when() on wildcard count but doesn't handle negative counts (impossible)
 *    or validate that the wildcard count matches actual wildcards in segments.
 *
 * 10. Consider adding a PathSpec.matches(pathSegments: PathSegments): Boolean for quick match testing
 *     without needing the full PathSpecMap infrastructure.
 */
