@file:Suppress("UNCHECKED_CAST")

package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.data.StringArrayFormat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.StringFormat

/**
 * A [PathSpec] with all of its wildcard values fulfilled.
 *
 * ResolvedPath represents a path specification where all typed wildcards have been filled with
 * concrete values. This is what you get in request handlers when a path is matched - the PathSpec
 * pattern plus the actual values extracted from the URL.
 *
 * For example, if your PathSpec is `/users/{userId}/posts/{postId}` and a request comes in for
 * `/users/john/posts/42`, the ResolvedPath would contain:
 * - pathSpec: the PathSpec2<String, Int> pattern
 * - rawPathArguments: ["john", 42]
 * - Accessible via type-safe `.arg1` and `.arg2` properties
 *
 * Example usage in a handler:
 * ```kotlin
 * val userPost = path.path("users").arg<String>("userId")
 *     .path("posts").arg<Int>("postId").get
 *
 * userPost bind HttpHandler { request ->
 *     val userId: String = request.path.arg1  // "john"
 *     val postId: Int = request.path.arg2     // 42
 *     // Handle request...
 * }
 * ```
 *
 * @param PATH The PathSpec type (PathSpec0, PathSpec1<A>, etc.)
 * @property pathSpec The path specification pattern that was matched
 * @property rawPathArguments The parsed wildcard values in order
 * @property trailingSegments Optional trailing path segments if the PathSpec uses [PathSpec.Afterwards.TrailingSegments]
 */
@ConsistentCopyVisibility
public data class ResolvedPath<out PATH : PathSpec> internal constructor(
    public val pathSpec: PATH,
    public val rawPathArguments: List<Any?>,
    public val trailingSegments: PathSegments? = null,
) {
    private val segments: List<Segment> by lazy {
        var index = 0
        pathSpec.segments.map {
            when (it) {
                is PathSpec.Segment.Constant -> Segment.Constant(it)
                is PathSpec.Segment.Wildcard<*> -> Segment.WildcardWithValue(
                    it as PathSpec.Segment.Wildcard<Any?>,
                    rawPathArguments[index++]
                )
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
            public constructor(segment: PathSpec.Segment.Wildcard<T>, value: T) : this(
                segment.name,
                segment.serializer,
                value
            )

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

context(serverRuntime: ServerRuntime)
public fun ResolvedPath<*>.pathSegments(): PathSegments =
    pathSegments(serverRuntime.externalSerialization.stringArrayFormat)

context(serverRuntime: ServerRuntime)
public fun ResolvedPath<*>.path(): String = path(serverRuntime.externalSerialization.stringArrayFormat)

context(serverRuntime: ServerRuntime)
public fun ResolvedPath<*>.fullUrl(): String = generalSettings().publicUrl + path()

context(serverRuntime: ServerRuntime)
public fun ResolvedPath<*>.wsFullUrl(): String = generalSettings().wsUrl + path()

/**
 * Interface for objects that can provide a [ResolvedPath] when given a [ServerRuntime] context.
 *
 * This is used for lazy path resolution where the path cannot be determined until runtime context is available.
 * Common use cases include paths that depend on server settings or need to look up endpoints.
 *
 * @param PATH The PathSpec type
 */
public interface HasContextualPath<out PATH : PathSpec> {
    context(server: ServerRuntime)
    public val pathInContext: ResolvedPath<PATH>
}

/**
 * Interface for objects that directly contain a [ResolvedPath].
 *
 * This is simpler than [HasContextualPath] - the path is already resolved and available immediately
 * without requiring server context. Request objects implement this interface.
 *
 * @param PATH The PathSpec type
 */
public interface HasResolvedPath<PATH : PathSpec> {
    public val path: ResolvedPath<PATH>
}

public fun ResolvedPath(path: PathSpec0, trailingWildcard: PathSegments? = null): ResolvedPath<PathSpec0> =
    ResolvedPath(path, emptyList(), trailingWildcard?.takeIf { path.after == PathSpec.Afterwards.TrailingSegments })

public fun <A> ResolvedPath(
    path: PathSpec1<A>,
    first: A,
    trailingWildcard: PathSegments? = null,
): ResolvedPath<PathSpec1<A>> =
    ResolvedPath(path, listOf(first), trailingWildcard?.takeIf { path.after == PathSpec.Afterwards.TrailingSegments })

public fun <A, B> ResolvedPath(
    path: PathSpec2<A, B>,
    first: A,
    second: B,
    trailingWildcard: PathSegments? = null,
): ResolvedPath<PathSpec2<A, B>> =
    ResolvedPath(
        path,
        listOf(first, second),
        trailingWildcard?.takeIf { path.after == PathSpec.Afterwards.TrailingSegments })

public fun <A, B, C> ResolvedPath(
    path: PathSpec3<A, B, C>,
    first: A,
    second: B,
    third: C,
    trailingWildcard: PathSegments? = null,
): ResolvedPath<PathSpec3<A, B, C>> =
    ResolvedPath(
        path,
        listOf(first, second, third),
        trailingWildcard?.takeIf { path.after == PathSpec.Afterwards.TrailingSegments })

public fun HasResolvedPath<*>.pathSegments(stringArrayFormat: StringArrayFormat): PathSegments =
    path.pathSegments(stringArrayFormat)

public fun HasResolvedPath<*>.path(stringArrayFormat: StringArrayFormat): String = path.path(stringArrayFormat)

context(serverRuntime: ServerRuntime)
public fun HasResolvedPath<*>.pathSegments(): PathSegments =
    path.pathSegments(serverRuntime.externalSerialization.stringArrayFormat)

context(serverRuntime: ServerRuntime)
public fun HasResolvedPath<*>.path(): String = path.path(serverRuntime.externalSerialization.stringArrayFormat)

context(serverRuntime: ServerRuntime)
public fun HasResolvedPath<*>.fullUrl(): String = generalSettings().publicUrl + path()

context(serverRuntime: ServerRuntime)
public fun HasResolvedPath<*>.wsFullUrl(): String = generalSettings().wsUrl + path()

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
public fun HasContextualPath<*>.pathSegments(): PathSegments =
    pathInContext.pathSegments(serverRuntime.externalSerialization.stringArrayFormat)

context(serverRuntime: ServerRuntime)
public fun HasContextualPath<*>.path(): String =
    pathInContext.path(serverRuntime.externalSerialization.stringArrayFormat)

context(serverRuntime: ServerRuntime)
public fun HasContextualPath<*>.fullUrl(): String = generalSettings().publicUrl + path()

context(serverRuntime: ServerRuntime)
public fun HasContextualPath<*>.wsFullUrl(): String = generalSettings().wsUrl + path()
