package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.RouteNotFoundException
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
public data class RawHttpEndpoint<out PATH: PathSpec>(val pathSegments: PathSegments, val method: HttpMethod): HasContextualPath<PATH> {
    public constructor(asString: String, method: HttpMethod): this(PathSegments.parse(asString), method)

    @Suppress("UNCHECKED_CAST")
    context(server: ServerRuntime)
    override val pathInContext: ResolvedPath<PATH> get() = match.path as ResolvedPath<PATH>

    @Transient private var matchIfPresent: PathSpecMap.Match<HttpHandler<*>>? = null

    context(server: ServerRuntime)
    public val match: PathSpecMap.Match<HttpHandler<*>> get() {
        if (this.matchIfPresent == null) {
            this.matchIfPresent = server.server.endpoints.match(server.externalSerialization.stringArrayFormat, pathSegments) { it.http[method] }
        }
        return this.matchIfPresent ?: throw RouteNotFoundException(this)
    }

    public constructor(
        pathSegments: PathSegments,
        method: HttpMethod,
        match: PathSpecMap.Match<HttpHandler<*>>
    ) : this(pathSegments, method) {
        this.matchIfPresent = match
    }

    override fun equals(other: Any?): Boolean =
        other is RawHttpEndpoint<*> && other.pathSegments == pathSegments && other.method == method
    override fun hashCode(): Int = 31 * pathSegments.hashCode() + method.hashCode()
    override fun toString(): String = "$method /$pathSegments"
}

context(server: ServerRuntime)
public fun <PATH : PathSpec> RawHttpEndpoint(path: ResolvedPath<PATH>, method: HttpMethod): RawHttpEndpoint<PATH> = RawHttpEndpoint(path.pathSegments(server.internalSerialization.stringArrayFormat), method = method)

context(serverRuntime: ServerRuntime)
public fun RawHttpEndpoint(spec: PathSpec0, method: HttpMethod, trailingSegments: PathSegments? = null): RawHttpEndpoint<PathSpec0> = RawHttpEndpoint(ResolvedPath(spec, trailingSegments), method)

context(serverRuntime: ServerRuntime)
public fun <A> RawHttpEndpoint(spec: PathSpec1<A>, path1: A, method: HttpMethod, trailingSegments: PathSegments? = null): RawHttpEndpoint<PathSpec1<A>> =
    RawHttpEndpoint(ResolvedPath(spec, path1, trailingSegments), method)

context(serverRuntime: ServerRuntime)
public fun <A, B> RawHttpEndpoint(spec: PathSpec2<A, B>, path1: A, path2: B, method: HttpMethod, trailingSegments: PathSegments? = null): RawHttpEndpoint<PathSpec2<A, B>> =
    RawHttpEndpoint(ResolvedPath(spec, path1, path2, trailingSegments), method)

context(serverRuntime: ServerRuntime)
public fun <A, B, C> RawHttpEndpoint(spec: PathSpec3<A, B, C>, path1: A, path2: B, path3: C, method: HttpMethod, trailingSegments: PathSegments? = null): RawHttpEndpoint<PathSpec3<A, B, C>> =
    RawHttpEndpoint(ResolvedPath(spec, path1, path2, path3, trailingSegments), method)