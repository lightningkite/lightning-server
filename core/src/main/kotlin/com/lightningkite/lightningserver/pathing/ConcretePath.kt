package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.data.StringArrayFormat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.StringFormat

public interface HasContextualPath<PATH: PathSpec> {
    context(server: ServerRuntime) public val pathInContext: ConcretePath<PATH>
}

/**
 * A [PathSpec] with all of its wildcard values fulfilled.
 */
public interface ConcretePath<PATH: PathSpec> : PathPredicate {
    public val pathSpec: PATH
    public val rawPathArguments: List<Any?>
    public val wildcard: List<String>? get() = null

    public sealed interface Segment {
        public val segment: PathSpec.Segment

        public data class Constant(override val segment: PathSpec.Segment.Constant) : Segment {
            public val value: String get() = segment.value
            override fun toString(): String = value
        }
        public data class WildcardWithValue<T>(override val segment: PathSpec.Segment.Wildcard<T>, val value: T) : Segment {
            public val name: String get() = segment.name
            public val serializer: KSerializer<T> get() = segment.serializer
            public fun toString(format: StringFormat): String = format.encodeToString(serializer, value)
        }
    }

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
}

@Suppress("UNCHECKED_CAST")
public val ConcretePath<*>.segments: List<ConcretePath.Segment> get() {
    var index = 0
    return pathSpec.segments.map {
        when (it) {
            is PathSpec.Segment.Constant -> ConcretePath.Segment.Constant(it)
            is PathSpec.Segment.Wildcard<*> -> ConcretePath.Segment.WildcardWithValue(it as PathSpec.Segment.Wildcard<Any?>, rawPathArguments[index++])
        }
    }
}

public fun ConcretePath<*>.pathSegments(stringArrayFormat: StringArrayFormat): List<String> =
    segments.map {
        when (it) {
            is ConcretePath.Segment.Constant -> it.value
            is ConcretePath.Segment.WildcardWithValue<*> -> it.toString(stringArrayFormat)
        }
    }

public fun ConcretePath<*>.path(stringArrayFormat: StringArrayFormat): String =
    pathSegments(stringArrayFormat).joinToString("/", postfix = if (pathSpec.after == PathSpec.Afterwards.TrailingSlash) "/" else "")

private data class BasicConcretePath<PATH: PathSpec>(
    override val pathSpec: PATH,
    override val rawPathArguments: List<Any?>,
    override val wildcard: List<String>?
) : ConcretePath<PATH>

public fun ConcretePath(path: PathSpec0, trailingWildcard: List<String>? = null): ConcretePath<PathSpec0> =
    BasicConcretePath(path, emptyList(), trailingWildcard?.takeIf { path.after == PathSpec.Afterwards.TrailingSegments })
public fun <A> ConcretePath(path: PathSpec1<A>, first: A, trailingWildcard: List<String>? = null): ConcretePath<PathSpec1<A>> =
    BasicConcretePath(path, listOf(first), trailingWildcard?.takeIf { path.after == PathSpec.Afterwards.TrailingSegments })
public fun <A, B> ConcretePath(path: PathSpec2<A, B>, first: A, second: B, trailingWildcard: List<String>? = null): ConcretePath<PathSpec2<A, B>> =
    BasicConcretePath(path, listOf(first, second), trailingWildcard?.takeIf { path.after == PathSpec.Afterwards.TrailingSegments })
public fun <A, B, C> ConcretePath(path: PathSpec3<A, B, C>, first: A, second: B, third: C, trailingWildcard: List<String>? = null): ConcretePath<PathSpec3<A, B, C>> =
    BasicConcretePath(path, listOf(first, second, third), trailingWildcard?.takeIf { path.after == PathSpec.Afterwards.TrailingSegments })


@Suppress("UNCHECKED_CAST")
@get:JvmName("first1")
public val <A> ConcretePath<PathSpec1<A>>.first: A get() = rawPathArguments[0] as A

@Suppress("UNCHECKED_CAST")
@get:JvmName("first2")
public val <A, B> ConcretePath<PathSpec2<A, B>>.first: A get() = rawPathArguments[0] as A

@Suppress("UNCHECKED_CAST")
@get:JvmName("second2")
public val <A, B> ConcretePath<PathSpec2<A, B>>.second: B get() = rawPathArguments[1] as B

@Suppress("UNCHECKED_CAST")
@get:JvmName("first3")
public val <A, B, C> ConcretePath<PathSpec3<A, B, C>>.first: A get() = rawPathArguments[0] as A

@Suppress("UNCHECKED_CAST")
@get:JvmName("second3")
public val <A, B, C> ConcretePath<PathSpec3<A, B, C>>.second: B get() = rawPathArguments[1] as B

@Suppress("UNCHECKED_CAST")
@get:JvmName("third3")
public val <A, B, C> ConcretePath<PathSpec3<A, B, C>>.third: C get() = rawPathArguments[1] as C



@Suppress("UNCHECKED_CAST")
@get:JvmName("first1")
context(serverRuntime: ServerRuntime)
public val <A> HasContextualPath<PathSpec1<A>>.first: A get() = pathInContext.rawPathArguments[0] as A

@Suppress("UNCHECKED_CAST")
@get:JvmName("first2")
context(serverRuntime: ServerRuntime)
public val <A, B> HasContextualPath<PathSpec2<A, B>>.first: A get() = pathInContext.rawPathArguments[0] as A

@Suppress("UNCHECKED_CAST")
@get:JvmName("second2")
context(serverRuntime: ServerRuntime)
public val <A, B> HasContextualPath<PathSpec2<A, B>>.second: B get() = pathInContext.rawPathArguments[1] as B

@Suppress("UNCHECKED_CAST")
@get:JvmName("first3")
context(serverRuntime: ServerRuntime)
public val <A, B, C> HasContextualPath<PathSpec3<A, B, C>>.first: A get() = pathInContext.rawPathArguments[0] as A

@Suppress("UNCHECKED_CAST")
@get:JvmName("second3")
context(serverRuntime: ServerRuntime)
public val <A, B, C> HasContextualPath<PathSpec3<A, B, C>>.second: B get() = pathInContext.rawPathArguments[1] as B

@Suppress("UNCHECKED_CAST")
@get:JvmName("third3")
context(serverRuntime: ServerRuntime)
public val <A, B, C> HasContextualPath<PathSpec3<A, B, C>>.third: C get() = pathInContext.rawPathArguments[1] as C
