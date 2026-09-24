package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.RouteNotFoundException
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.runtime.Engine
import com.lightningkite.services.data.Unsafe
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
@ConsistentCopyVisibility
public data class RawHttpEndpoint<PATH : PathSpec> private constructor(
    val pathSegments: PathSegments,
    val method: HttpMethod,
) : HasContextualPath<PATH> {
    @Transient
    private var matchIfPresent: PathSpecMap.Match<HttpHandler<PATH>>? = null

    @Transient
    private var searched: Boolean = false

    context(server: Engine)
    public fun tryResolve(): PathSpecMap.Match<HttpHandler<PATH>>? {
        if (searched) return matchIfPresent
        searched = true

        val match = server.server.endpoints.match(
            server.externalSerialization.stringArrayFormat,
            pathSegments
        ) { it.http[method] }

        @Suppress("UNCHECKED_CAST")
        if (match != null) match as PathSpecMap.Match<HttpHandler<PATH>>

        matchIfPresent = match

        return match
    }

    context(server: Engine)
    public fun resolve(): PathSpecMap.Match<HttpHandler<PATH>> = tryResolve() ?: throw RouteNotFoundException(this)

    @Suppress("UNCHECKED_CAST")
    context(server: Engine)
    override val pathInContext: ResolvedPath<PATH> get() = resolve().path as ResolvedPath<PATH>

    override fun equals(other: Any?): Boolean =
        other is RawHttpEndpoint<*> && other.pathSegments == pathSegments && other.method == method

    override fun hashCode(): Int = 31 * pathSegments.hashCode() + method.hashCode()
    override fun toString(): String = "$method /$pathSegments"

    private fun pinnedTo(match: PathSpecMap.Match<HttpHandler<PATH>>?): RawHttpEndpoint<PATH> = apply {
        matchIfPresent = match
        searched = true
    }

    public companion object {
        @Unsafe("The path segments and method must route to an endpoint of type PATH, or to nothing")
        public fun <PATH : PathSpec> fromPathSegments(
            pathSegments: PathSegments,
            method: HttpMethod,
        ): RawHttpEndpoint<PATH> = RawHttpEndpoint<PATH>(pathSegments, method)

        /** An endpoint that resolves to [match] without routing, at the path [match] was resolved from. */
        @Unsafe("The match must be for an endpoint of type PATH")
        context(server: Engine)
        public fun <PATH : PathSpec> fromMatch(
            method: HttpMethod,
            match: PathSpecMap.Match<HttpHandler<PATH>>,
        ): RawHttpEndpoint<PATH> = RawHttpEndpoint<PATH>(match.path.pathSegments(), method).pinnedTo(match)

        /**
         * An endpoint that resolves to nothing, even where a route would claim its segments.
         *
         * Only until serialized: a deserialized copy routes its segments like any other endpoint.
         */
        @Unsafe("The path segments and method must route to an endpoint of type PATH, or to nothing")
        public fun <PATH : PathSpec> unresolvable(
            pathSegments: PathSegments,
            method: HttpMethod,
        ): RawHttpEndpoint<PATH> = RawHttpEndpoint<PATH>(pathSegments, method).pinnedTo(null)
    }
}

/** An endpoint that has not been routed yet, so claims no particular [PathSpec]. */
public fun RawHttpEndpoint(pathSegments: PathSegments, method: HttpMethod): RawHttpEndpoint<PathSpec> =
    @OptIn(Unsafe::class) // SAFETY: Every endpoint is a PathSpec
    RawHttpEndpoint.fromPathSegments(pathSegments, method)

/** An endpoint that has not been routed yet, so claims no particular [PathSpec]. */
public fun RawHttpEndpoint(asString: String, method: HttpMethod): RawHttpEndpoint<PathSpec> =
    RawHttpEndpoint(PathSegments.parse(asString), method)

/** Resolves only to the handler registered at [path]'s own spec, even if another route would claim its segments. */
@OptIn(Unsafe::class)
context(server: Engine)
public fun <PATH : PathSpec> RawHttpEndpoint(path: ResolvedPath<PATH>, method: HttpMethod): RawHttpEndpoint<PATH> {
    // Pinned rather than routed: a more specific route (/users/me over /users/{id}) would otherwise
    // claim these segments under a different PathSpec than PATH.
    @Suppress("UNCHECKED_CAST")
    val handler = server.server.endpoints[path.pathSpec]?.http?.get(method) as HttpHandler<PATH>?
    return if (handler != null) {
        // SAFETY: The match is for PATH's own spec, and handlers are registered under the spec they take
        RawHttpEndpoint.fromMatch(method, PathSpecMap.Match(path, handler))
    } else {
        // SAFETY: Resolves to nothing here. A deserialized copy routes these segments again, trusting, as every
        // serialized endpoint does, that no more specific route claims them.
        RawHttpEndpoint.unresolvable(path.pathSegments(), method)
    }
}

context(engine: Engine)
public fun RawHttpEndpoint(
    spec: PathSpec0,
    method: HttpMethod,
    trailingSegments: PathSegments? = null,
): RawHttpEndpoint<PathSpec0> = RawHttpEndpoint(ResolvedPath(spec, trailingSegments), method)

context(engine: Engine)
public fun <A> RawHttpEndpoint(
    spec: PathSpec1<A>,
    path1: A,
    method: HttpMethod,
    trailingSegments: PathSegments? = null,
): RawHttpEndpoint<PathSpec1<A>> =
    RawHttpEndpoint(ResolvedPath(spec, path1, trailingSegments), method)

context(engine: Engine)
public fun <A, B> RawHttpEndpoint(
    spec: PathSpec2<A, B>,
    path1: A,
    path2: B,
    method: HttpMethod,
    trailingSegments: PathSegments? = null,
): RawHttpEndpoint<PathSpec2<A, B>> =
    RawHttpEndpoint(ResolvedPath(spec, path1, path2, trailingSegments), method)

context(engine: Engine)
public fun <A, B, C> RawHttpEndpoint(
    spec: PathSpec3<A, B, C>,
    path1: A,
    path2: B,
    path3: C,
    method: HttpMethod,
    trailingSegments: PathSegments? = null,
): RawHttpEndpoint<PathSpec3<A, B, C>> =
    RawHttpEndpoint(ResolvedPath(spec, path1, path2, path3, trailingSegments), method)

context(server: Engine)
public fun RawHttpEndpoint<*>.route(): String =
    tryResolve()?.pathSpec?.toString() ?: "/$pathSegments"