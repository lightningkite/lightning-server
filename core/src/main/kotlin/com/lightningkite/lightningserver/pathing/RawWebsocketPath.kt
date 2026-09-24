package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.runtime.Engine
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Represents an unresolved WebSocket path that will be matched against registered endpoints.
 *
 * Similar to [RawHttpEndpoint] but for WebSocket connections. The path is parsed into segments
 * and matched against the server's registered WebSocket handlers when accessed in an Engine context.
 *
 * **Usage:**
 * ```kotlin
 * context(serverRuntime) {
 *     val wsPath = RawWebSocketPath<PathSpec0>("/chat")
 *     val resolved = wsPath.pathInContext // Matches against registered WebSocket paths
 * }
 * ```
 *
 * @param PATH The path specification type that this path will be matched against
 * @property pathSegments The parsed path segments
 *
 * @see RawHttpEndpoint
 * @see HasContextualPath
 */
@Serializable(RawWebSocketPath.Serializer::class)
public class RawWebSocketPath<PATH : PathSpec>(public val pathSegments: PathSegments) : HasContextualPath<PATH> {
    /** Constructs a raw WebSocket path from a string. */
    public constructor(path: String) : this(PathSegments.parse(path))

    private var matchIfPresent: PathSpecMap.Match<WebSocketHandler<PATH, *>>? = null

    private var searched: Boolean = false

    context(server: Engine)
    public fun tryResolve(): PathSpecMap.Match<WebSocketHandler<PATH, *>>? {
        if (searched) return matchIfPresent
        searched = true

        val match = server.server.endpoints.match(
            server.externalSerialization.stringArrayFormat,
            pathSegments
        ) { it.webSocket }

        @Suppress("UNCHECKED_CAST")
        if (match != null) match as PathSpecMap.Match<WebSocketHandler<PATH, *>>

        matchIfPresent = match

        return match
    }

    context(server: Engine)
    public val matchOrNull: PathSpecMap.Match<WebSocketHandler<*, *>>?
        get() = tryResolve()

    context(server: Engine)
    public fun resolve(): PathSpecMap.Match<WebSocketHandler<*, *>> =
        tryResolve() ?: throw NullPointerException("No match for path: $pathSegments. Registered paths are ${server.server.endpoints.keys}")

    @Suppress("UNCHECKED_CAST")
    context(server: Engine)
    override val pathInContext: ResolvedPath<PATH> get() = resolve().path as ResolvedPath<PATH>

    public constructor(
        pathSegments: PathSegments,
        match: PathSpecMap.Match<WebSocketHandler<PATH, *>>,
    ) : this(pathSegments) {
        this.matchIfPresent = match
    }

    override fun equals(other: Any?): Boolean = other is RawWebSocketPath<*> && other.pathSegments == pathSegments
    override fun hashCode(): Int = pathSegments.hashCode() + 1
    override fun toString(): String = "/$pathSegments"

    public class Serializer<T : PathSpec>(ignored: KSerializer<T>) : KSerializer<RawWebSocketPath<T>> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor(" com.lightningkite.lightningserver.pathing.RawWebSocketPath", PrimitiveKind.STRING)

        override fun serialize(
            encoder: Encoder,
            value: RawWebSocketPath<T>,
        ) {
            encoder.encodeString(value.pathSegments.toString())
        }

        override fun deserialize(decoder: Decoder): RawWebSocketPath<T> {
            return RawWebSocketPath<T>(PathSegments.parse(decoder.decodeString()))
        }
    }
}

context(server: Engine)
public fun <PATH : PathSpec> RawWebSocketPath(path: ResolvedPath<PATH>): RawWebSocketPath<PATH> =
    RawWebSocketPath(path.pathSegments(server.internalSerialization.stringArrayFormat))

context(engine: Engine)
public fun RawWebSocketPath(spec: PathSpec0, trailingSegments: PathSegments? = null): RawWebSocketPath<PathSpec0> =
    RawWebSocketPath(ResolvedPath(spec, trailingSegments))

context(engine: Engine)
public fun <A> RawWebSocketPath(
    spec: PathSpec1<A>,
    path1: A,
    trailingSegments: PathSegments? = null,
): RawWebSocketPath<PathSpec1<A>> =
    RawWebSocketPath(ResolvedPath(spec, path1, trailingSegments))

context(engine: Engine)
public fun <A, B> RawWebSocketPath(
    spec: PathSpec2<A, B>,
    path1: A,
    path2: B,
    trailingSegments: PathSegments? = null,
): RawWebSocketPath<PathSpec2<A, B>> =
    RawWebSocketPath(ResolvedPath(spec, path1, path2, trailingSegments))

context(engine: Engine)
public fun <A, B, C> RawWebSocketPath(
    spec: PathSpec3<A, B, C>,
    path1: A,
    path2: B,
    path3: C,
    trailingSegments: PathSegments? = null,
): RawWebSocketPath<PathSpec3<A, B, C>> =
    RawWebSocketPath(ResolvedPath(spec, path1, path2, path3, trailingSegments))

context(engine: Engine)
public fun RawWebSocketPath<*>.route(): String =
    tryResolve()?.pathSpec?.toString() ?: "/$pathSegments"