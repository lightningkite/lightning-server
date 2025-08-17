@file:Suppress("UNCHECKED_CAST")

package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.data.StringArrayFormat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.StringFormat

/**
 * A [PathSpec] with all of its wildcard values fulfilled.
 */
public class ConcretePath<PATH: PathSpec> internal constructor(
    public val pathSpec: PATH,
    public val rawPathArguments: List<Any?>,
    public val wildcard: List<String>? = null,
) : PathPredicate {
    public val segments: List<Segment> by lazy {
        var index = 0
        pathSpec.segments.map {
            when (it) {
                is PathSpec.Segment.Constant -> Segment.Constant(it)
                is PathSpec.Segment.Wildcard<*> -> Segment.WildcardWithValue(it as PathSpec.Segment.Wildcard<Any?>, rawPathArguments[index++])
            }
        }
    }

    public sealed interface Segment {
        public val segment: PathSpec.Segment

        public data class Constant(override val segment: PathSpec.Segment.Constant) : Segment {
            public val value: String get() = segment.value
            override fun toString(): String = value
        }
        public data class WildcardWithValue<T>(override val segment: PathSpec.Segment.Wildcard<T>, val value: T) : Segment {
            public val name: String get() = segment.name
            public val serializer: KSerializer<T> get() = segment.serializer
            override fun toString(): String = "{$name=$value}"
            public fun toString(format: StringFormat): String = format.encodeToString(serializer, value)
        }
    }

    // PathPredicate overloads
    override fun satisfiedBy(path: PathSpec): Boolean = false // only matches concrete paths with the same arguments
    override fun satisfiedBy(path: ConcretePath<*>): Boolean {
        val predicateSegments = segments
        val otherSegments = path.segments
        if (otherSegments.size != predicateSegments.size) return false
        for ((idx, segment) in predicateSegments.withIndex()) {
            if (segment != otherSegments.getOrNull(idx)) return false
        }
        return true
    }

    private fun <T> List<T>.joinToPath() = joinToString("/", prefix = "/", postfix = if (pathSpec.after == PathSpec.Afterwards.TrailingSlash) "/" else "")

    override fun toString(): String = segments.joinToPath()

    public fun pathSegments(stringArrayFormat: StringArrayFormat): List<String> =
        segments.map {
            when (it) {
                is Segment.Constant -> it.toString()
                is Segment.WildcardWithValue<*> -> it.toString(stringArrayFormat)
            }
        }

    public fun path(stringArrayFormat: StringArrayFormat): String =
        pathSegments(stringArrayFormat).plus(wildcard ?: emptyList()).joinToPath()

    public fun toString(stringArrayFormat: StringArrayFormat): String = path(stringArrayFormat)
}

public interface HasConcretePath<PATH : PathSpec> {
    public val path: ConcretePath<PATH>
}

public interface HasContextualPath<PATH : PathSpec> {
    context(server: ServerRuntime) public val pathInContext: ConcretePath<PATH>
}

public fun ConcretePath(path: PathSpec0, trailingWildcard: List<String>? = null): ConcretePath<PathSpec0> =
    ConcretePath(path, emptyList(), trailingWildcard?.takeIf { path.after == PathSpec.Afterwards.TrailingSegments })
public fun <A> ConcretePath(path: PathSpec1<A>, first: A, trailingWildcard: List<String>? = null): ConcretePath<PathSpec1<A>> =
    ConcretePath(path, listOf(first), trailingWildcard?.takeIf { path.after == PathSpec.Afterwards.TrailingSegments })
public fun <A, B> ConcretePath(path: PathSpec2<A, B>, first: A, second: B, trailingWildcard: List<String>? = null): ConcretePath<PathSpec2<A, B>> =
    ConcretePath(path, listOf(first, second), trailingWildcard?.takeIf { path.after == PathSpec.Afterwards.TrailingSegments })
public fun <A, B, C> ConcretePath(path: PathSpec3<A, B, C>, first: A, second: B, third: C, trailingWildcard: List<String>? = null): ConcretePath<PathSpec3<A, B, C>> =
    ConcretePath(path, listOf(first, second, third), trailingWildcard?.takeIf { path.after == PathSpec.Afterwards.TrailingSegments })

public fun HasConcretePath<*>.pathSegments(stringArrayFormat: StringArrayFormat): List<String> = path.pathSegments(stringArrayFormat)
public fun HasConcretePath<*>.path(stringArrayFormat: StringArrayFormat): String = path.path(stringArrayFormat)

@get:JvmName("first1")
public val <A> ConcretePath<PathSpec1<A>>.first: A get() = rawPathArguments[0] as A

@get:JvmName("first2")
public val <A, B> ConcretePath<PathSpec2<A, B>>.first: A get() = rawPathArguments[0] as A

@get:JvmName("second2")
public inline val <A, B> ConcretePath<PathSpec2<A, B>>.second: B get() = rawPathArguments[1] as B

@get:JvmName("first3")
public inline val <A, B, C> ConcretePath<PathSpec3<A, B, C>>.first: A get() = rawPathArguments[0] as A

@get:JvmName("second3")
public inline val <A, B, C> ConcretePath<PathSpec3<A, B, C>>.second: B get() = rawPathArguments[1] as B

@get:JvmName("third3")
public inline val <A, B, C> ConcretePath<PathSpec3<A, B, C>>.third: C get() = rawPathArguments[1] as C


@get:JvmName("first1")
public val <A> HasConcretePath<PathSpec1<A>>.first: A get() = path.first

@get:JvmName("first2")
public val <A, B> HasConcretePath<PathSpec2<A, B>>.first: A get() = path.first

@get:JvmName("second2")
public val <A, B> HasConcretePath<PathSpec2<A, B>>.second: B get() = path.second

@get:JvmName("first3")
public val <A, B, C> HasConcretePath<PathSpec3<A, B, C>>.first: A get() = path.first

@get:JvmName("second3")
public val <A, B, C> HasConcretePath<PathSpec3<A, B, C>>.second: B get() = path.second

@get:JvmName("third3")
public val <A, B, C> HasConcretePath<PathSpec3<A, B, C>>.third: C get() = path.third


@get:JvmName("first1")
context(serverRuntime: ServerRuntime)
public val <A> HasContextualPath<PathSpec1<A>>.first: A get() = pathInContext.first

@get:JvmName("first2")
context(serverRuntime: ServerRuntime)
public val <A, B> HasContextualPath<PathSpec2<A, B>>.first: A get() = pathInContext.first

@get:JvmName("second2")
context(serverRuntime: ServerRuntime)
public val <A, B> HasContextualPath<PathSpec2<A, B>>.second: B get() = pathInContext.second

@get:JvmName("first3")
context(serverRuntime: ServerRuntime)
public val <A, B, C> HasContextualPath<PathSpec3<A, B, C>>.first: A get() = pathInContext.first

@get:JvmName("second3")
context(serverRuntime: ServerRuntime)
public val <A, B, C> HasContextualPath<PathSpec3<A, B, C>>.second: B get() = pathInContext.second

@get:JvmName("third3")
context(serverRuntime: ServerRuntime)
public val <A, B, C> HasContextualPath<PathSpec3<A, B, C>>.third: C get() = pathInContext.third
