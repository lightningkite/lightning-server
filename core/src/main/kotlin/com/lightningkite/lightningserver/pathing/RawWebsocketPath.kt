package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.runtime.Engine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Custom serializer for [RawWebSocketPath] that stores the path as a string.
 */
public class PathSerializer<T : PathSpec>(ignored: KSerializer<T>) : KSerializer<RawWebSocketPath<T>> {
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
@Serializable(with = PathSerializer::class)
public class RawWebSocketPath<out PATH : PathSpec>(public val pathSegments: PathSegments) : HasContextualPath<PATH> {
    /** Constructs a raw WebSocket path from a string. */
    public constructor(path: String) : this(PathSegments.parse(path))

    @Suppress("UNCHECKED_CAST")
    context(server: Engine)
    override val pathInContext: ResolvedPath<PATH> get() = match.path as ResolvedPath<PATH>

    private var matchIfPresent: PathSpecMap.Match<*>? = null

    context(server: Engine)
    public val match: PathSpecMap.Match<*>
        get() {
            if (this.matchIfPresent == null) {
                this.matchIfPresent = server.server.endpoints.match(
                    server.externalSerialization.stringArrayFormat,
                    pathSegments
                ) { it.webSocket }
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

    override fun equals(other: Any?): Boolean = other is RawWebSocketPath<*> && other.pathSegments == pathSegments
    override fun hashCode(): Int = pathSegments.hashCode() + 1
    override fun toString(): String = "/$pathSegments"
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