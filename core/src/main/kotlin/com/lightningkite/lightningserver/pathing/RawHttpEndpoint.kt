package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.RouteNotFoundException
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
public class RawHttpEndpoint<out PATH: PathSpec>(public val asString: String, public val method: HttpMethod): HasContextualPath<PATH> {
    @Suppress("UNCHECKED_CAST")
    context(server: ServerRuntime)
    override val pathInContext: ConcretePath<PATH> get() = match.path as ConcretePath<PATH>

    @Transient private var matchIfPresent: PathSpecMap.Match<HttpHandler<*>>? = null

    context(server: ServerRuntime)
    public val match: PathSpecMap.Match<HttpHandler<*>> get() {
        if (this.matchIfPresent == null) {
            this.matchIfPresent = server.server.endpoints.match(server.externalSerialization.stringArrayFormat, asString) { it.http[method] }
        }
        return this.matchIfPresent ?: throw RouteNotFoundException(this)
    }

    public constructor(
        asString: String,
        method: HttpMethod,
        match: PathSpecMap.Match<HttpHandler<*>>
    ) : this(asString, method) {
        this.matchIfPresent = match
    }

    override fun equals(other: Any?): Boolean = other is RawHttpEndpoint<*> && other.asString == asString
    override fun hashCode(): Int = asString.hashCode() + 1
    override fun toString(): String = "$method $asString"
}

context(server: ServerRuntime)
public fun <PATH : PathSpec> RawHttpEndpoint(path: ConcretePath<PATH>, method: HttpMethod): RawHttpEndpoint<PATH> = RawHttpEndpoint(path.path(server.internalSerialization.stringArrayFormat), method = method)

context(serverRuntime: ServerRuntime)
public fun RawHttpEndpoint(spec: PathSpec0, method: HttpMethod, trailingSegments: ConcretePath.TrailingSegments? = null): RawHttpEndpoint<PathSpec0> = RawHttpEndpoint(ConcretePath(spec, trailingSegments), method)

context(serverRuntime: ServerRuntime)
public fun <A> RawHttpEndpoint(spec: PathSpec1<A>, path1: A, method: HttpMethod, trailingSegments: ConcretePath.TrailingSegments? = null): RawHttpEndpoint<PathSpec1<A>> =
    RawHttpEndpoint(ConcretePath(spec, path1, trailingSegments), method)

context(serverRuntime: ServerRuntime)
public fun <A, B> RawHttpEndpoint(spec: PathSpec2<A, B>, path1: A, path2: B, method: HttpMethod, trailingSegments: ConcretePath.TrailingSegments? = null): RawHttpEndpoint<PathSpec2<A, B>> =
    RawHttpEndpoint(ConcretePath(spec, path1, path2, trailingSegments), method)

context(serverRuntime: ServerRuntime)
public fun <A, B, C> RawHttpEndpoint(spec: PathSpec3<A, B, C>, path1: A, path2: B, path3: C, method: HttpMethod, trailingSegments: ConcretePath.TrailingSegments? = null): RawHttpEndpoint<PathSpec3<A, B, C>> =
    RawHttpEndpoint(ConcretePath(spec, path1, path2, path3, trailingSegments), method)