package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Custom serializer for [RawWebsocketPath] that stores the path as a string.
 */
public class PathSerializer<T : PathSpec>(ignored: KSerializer<T>) : KSerializer<RawWebsocketPath<T>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(" com.lightningkite.lightningserver.pathing.RawWebsocketPath", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: RawWebsocketPath<T>,
    ) {
        encoder.encodeString(value.pathSegments.toString())
    }

    override fun deserialize(decoder: Decoder): RawWebsocketPath<T> {
        return RawWebsocketPath<T>(PathSegments.parse(decoder.decodeString()))
    }
}

/**
 * Represents an unresolved WebSocket path that will be matched against registered endpoints.
 *
 * Similar to [RawHttpEndpoint] but for WebSocket connections. The path is parsed into segments
 * and matched against the server's registered WebSocket handlers when accessed in a ServerRuntime context.
 *
 * **Usage:**
 * ```kotlin
 * context(serverRuntime) {
 *     val wsPath = RawWebsocketPath<PathSpec0>("/chat")
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
@Serializable(with = PathSerializer::class)
public class RawWebsocketPath<PATH : PathSpec>(public val pathSegments: PathSegments) : HasContextualPath<PATH> {
    /** Constructs a raw WebSocket path from a string. */
    public constructor(path: String) : this(PathSegments.parse(path))

    @Suppress("UNCHECKED_CAST")
    context(server: ServerRuntime)
    override val pathInContext: ResolvedPath<PATH> get() = match.path as ResolvedPath<PATH>

    private var matchIfPresent: PathSpecMap.Match<*>? = null

    context(server: ServerRuntime)
    public val match: PathSpecMap.Match<*>
        get() {
            if (this.matchIfPresent == null) {
                this.matchIfPresent = server.server.endpoints.match(
                    server.externalSerialization.stringArrayFormat,
                    pathSegments
                ) { it.websocket }
            }
            return this.matchIfPresent
                ?: throw NullPointerException("No match for path: $pathSegments. Registered paths are ${server.server.endpoints.keys}")
        }

    public constructor(
        pathSegments: PathSegments,
        match: PathSpecMap.Match<*>,
    ) : this(pathSegments) {
        this.matchIfPresent = match
    }

    override fun equals(other: Any?): Boolean = other is RawWebsocketPath<*> && other.pathSegments == pathSegments
    override fun hashCode(): Int = pathSegments.hashCode() + 1
    override fun toString(): String = "/$pathSegments"
}

context(server: ServerRuntime)
public fun <PATH : PathSpec> RawWebsocketPath(path: ResolvedPath<PATH>): RawWebsocketPath<PATH> =
    RawWebsocketPath(path.pathSegments(server.internalSerialization.stringArrayFormat))

context(serverRuntime: ServerRuntime)
public fun RawWebsocketPath(spec: PathSpec0, trailingSegments: PathSegments? = null): RawWebsocketPath<PathSpec0> =
    RawWebsocketPath(ResolvedPath(spec, trailingSegments))

context(serverRuntime: ServerRuntime)
public fun <A> RawWebsocketPath(
    spec: PathSpec1<A>,
    path1: A,
    trailingSegments: PathSegments? = null,
): RawWebsocketPath<PathSpec1<A>> =
    RawWebsocketPath(ResolvedPath(spec, path1, trailingSegments))

context(serverRuntime: ServerRuntime)
public fun <A, B> RawWebsocketPath(
    spec: PathSpec2<A, B>,
    path1: A,
    path2: B,
    trailingSegments: PathSegments? = null,
): RawWebsocketPath<PathSpec2<A, B>> =
    RawWebsocketPath(ResolvedPath(spec, path1, path2, trailingSegments))

context(serverRuntime: ServerRuntime)
public fun <A, B, C> RawWebsocketPath(
    spec: PathSpec3<A, B, C>,
    path1: A,
    path2: B,
    path3: C,
    trailingSegments: PathSegments? = null,
): RawWebsocketPath<PathSpec3<A, B, C>> =
    RawWebsocketPath(ResolvedPath(spec, path1, path2, path3, trailingSegments))