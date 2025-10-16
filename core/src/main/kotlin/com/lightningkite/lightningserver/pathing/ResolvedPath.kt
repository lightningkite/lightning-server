@file:Suppress("UNCHECKED_CAST")

package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.data.StringArrayFormat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.StringFormat

/**
 * A [PathSpec] with all of its wildcard values fulfilled.
 */
@ConsistentCopyVisibility
public data class ResolvedPath<out PATH: PathSpec> internal constructor(
    public val pathSpec: PATH,
    public val rawPathArguments: List<Any?>,
    public val trailingSegments: PathSegments? = null,
) {
    private val segments: List<Segment> by lazy {
        var index = 0
        pathSpec.segments.map {
            when (it) {
                is PathSpec.Segment.Constant -> Segment.Constant(it)
                is PathSpec.Segment.Wildcard<*> -> Segment.WildcardWithValue(it as PathSpec.Segment.Wildcard<Any?>, rawPathArguments[index++])
            }
        } + (trailingSegments?.segments?.map(Segment::Constant) ?: emptyList())
    }

    private sealed interface Segment {
        fun toString(format: StringFormat): String

        data class Constant(val value: String) : Segment {
            public constructor(segment: PathSpec.Segment.Constant) : this(segment.value)

            override fun toString(): String = value
            override fun toString(format: StringFormat): String = value
        }
        data class WildcardWithValue<T>(val name: String, val serializer: KSerializer<T>, val value: T) : Segment {
            public constructor(segment: PathSpec.Segment.Wildcard<T>, value: T) : this(segment.name, segment.serializer, value)

            override fun toString(): String = "{$name=$value}"
            override fun toString(format: StringFormat): String = format.encodeToString(serializer, value)
        }
    }

    override fun toString(): String =
        segments.joinToString(prefix = "/", separator = "/")

    public fun pathSegments(stringArrayFormat: StringArrayFormat): PathSegments =
        PathSegments(segments.map { it.toString(stringArrayFormat) })

    public fun path(stringArrayFormat: StringArrayFormat): String =
        segments.joinToString(prefix = "/", separator = "/") { it.toString(stringArrayFormat) }

    public fun toString(stringArrayFormat: StringArrayFormat): String = path(stringArrayFormat)
}

public interface HasContextualPath<out PATH : PathSpec> {
    context(server: ServerRuntime) public val pathInContext: ResolvedPath<PATH>
}

public interface HasResolvedPath<PATH : PathSpec> {
    public val path: ResolvedPath<PATH>
}

public fun ResolvedPath(path: PathSpec0, trailingWildcard: PathSegments? = null): ResolvedPath<PathSpec0> =
    ResolvedPath(path, emptyList(), trailingWildcard?.takeIf { path.after == PathSpec.Afterwards.TrailingSegments })
public fun <A> ResolvedPath(path: PathSpec1<A>, first: A, trailingWildcard: PathSegments? = null): ResolvedPath<PathSpec1<A>> =
    ResolvedPath(path, listOf(first), trailingWildcard?.takeIf { path.after == PathSpec.Afterwards.TrailingSegments })
public fun <A, B> ResolvedPath(path: PathSpec2<A, B>, first: A, second: B, trailingWildcard: PathSegments? = null): ResolvedPath<PathSpec2<A, B>> =
    ResolvedPath(path, listOf(first, second), trailingWildcard?.takeIf { path.after == PathSpec.Afterwards.TrailingSegments })
public fun <A, B, C> ResolvedPath(path: PathSpec3<A, B, C>, first: A, second: B, third: C, trailingWildcard: PathSegments? = null): ResolvedPath<PathSpec3<A, B, C>> =
    ResolvedPath(path, listOf(first, second, third), trailingWildcard?.takeIf { path.after == PathSpec.Afterwards.TrailingSegments })

public fun HasResolvedPath<*>.pathSegments(stringArrayFormat: StringArrayFormat): PathSegments = path.pathSegments(stringArrayFormat)
public fun HasResolvedPath<*>.path(stringArrayFormat: StringArrayFormat): String = path.path(stringArrayFormat)

@get:JvmName("arg1_1")
public val <A> ResolvedPath<PathSpec1<A>>.arg1: A get() = rawPathArguments[0] as A

@get:JvmName("arg1_2")
public val <A, B> ResolvedPath<PathSpec2<A, B>>.arg1: A get() = rawPathArguments[0] as A

@get:JvmName("arg2_2")
public inline val <A, B> ResolvedPath<PathSpec2<A, B>>.arg2: B get() = rawPathArguments[1] as B

@get:JvmName("arg1_3")
public inline val <A, B, C> ResolvedPath<PathSpec3<A, B, C>>.arg1: A get() = rawPathArguments[0] as A

@get:JvmName("arg2_3")
public inline val <A, B, C> ResolvedPath<PathSpec3<A, B, C>>.arg2: B get() = rawPathArguments[1] as B

@get:JvmName("arg3_3")
public inline val <A, B, C> ResolvedPath<PathSpec3<A, B, C>>.arg3: C get() = rawPathArguments[2] as C



public val HasResolvedPath<*>.trailingSegments: PathSegments? get() = path.trailingSegments

@get:JvmName("arg1_1")
public val <A> HasResolvedPath<PathSpec1<A>>.arg1: A get() = path.arg1

@get:JvmName("arg1_2")
public val <A, B> HasResolvedPath<PathSpec2<A, B>>.arg1: A get() = path.arg1

@get:JvmName("arg2_2")
public val <A, B> HasResolvedPath<PathSpec2<A, B>>.arg2: B get() = path.arg2

@get:JvmName("arg1_3")
public val <A, B, C> HasResolvedPath<PathSpec3<A, B, C>>.arg1: A get() = path.arg1

@get:JvmName("arg2_3")
public val <A, B, C> HasResolvedPath<PathSpec3<A, B, C>>.arg2: B get() = path.arg2

@get:JvmName("arg3_3")
public val <A, B, C> HasResolvedPath<PathSpec3<A, B, C>>.arg3: C get() = path.arg3


context(serverRuntime: ServerRuntime)
public val HasContextualPath<*>.trailingSegments: PathSegments? get() = pathInContext.trailingSegments

@get:JvmName("arg1_1")
context(serverRuntime: ServerRuntime)
public val <A> HasContextualPath<PathSpec1<A>>.arg1: A get() = pathInContext.arg1

@get:JvmName("arg1_2")
context(serverRuntime: ServerRuntime)
public val <A, B> HasContextualPath<PathSpec2<A, B>>.arg1: A get() = pathInContext.arg1

@get:JvmName("arg2_2")
context(serverRuntime: ServerRuntime)
public val <A, B> HasContextualPath<PathSpec2<A, B>>.arg2: B get() = pathInContext.arg2

@get:JvmName("arg1_3")
context(serverRuntime: ServerRuntime)
public val <A, B, C> HasContextualPath<PathSpec3<A, B, C>>.arg1: A get() = pathInContext.arg1

@get:JvmName("arg2_3")
context(serverRuntime: ServerRuntime)
public val <A, B, C> HasContextualPath<PathSpec3<A, B, C>>.arg2: B get() = pathInContext.arg2

@get:JvmName("arg3_3")
context(serverRuntime: ServerRuntime)
public val <A, B, C> HasContextualPath<PathSpec3<A, B, C>>.arg3: C get() = pathInContext.arg3

context(serverRuntime: ServerRuntime)
public fun HasContextualPath<*>.pathSegments(stringArrayFormat: StringArrayFormat): PathSegments = pathInContext.pathSegments(stringArrayFormat)
context(serverRuntime: ServerRuntime)
public fun HasContextualPath<*>.path(stringArrayFormat: StringArrayFormat): String = pathInContext.path(stringArrayFormat)