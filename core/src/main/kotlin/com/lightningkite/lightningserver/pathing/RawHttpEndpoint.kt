package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.RouteNotFoundException
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.runtime.Engine
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
public data class RawHttpEndpoint<PATH : PathSpec>(val pathSegments: PathSegments, val method: HttpMethod) : HasContextualPath<PATH> {
    public constructor(asString: String, method: HttpMethod) : this(PathSegments.parse(asString), method)

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

    public constructor(
        pathSegments: PathSegments,
        method: HttpMethod,
        match: PathSpecMap.Match<HttpHandler<PATH>>,
    ) : this(pathSegments, method) {
        this.matchIfPresent = match
    }

    override fun equals(other: Any?): Boolean =
        other is RawHttpEndpoint<*> && other.pathSegments == pathSegments && other.method == method

    override fun hashCode(): Int = 31 * pathSegments.hashCode() + method.hashCode()
    override fun toString(): String = "$method /$pathSegments"
}

context(server: Engine)
public fun <PATH : PathSpec> RawHttpEndpoint(path: ResolvedPath<PATH>, method: HttpMethod): RawHttpEndpoint<PATH> =
    RawHttpEndpoint(path.pathSegments(server.internalSerialization.stringArrayFormat), method = method)

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
    tryResolve()?.pathSpec?.toString() ?: "/${pathSegments}"