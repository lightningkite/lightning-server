package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.runtime.Engine
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.services.data.Unsafe
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
 *     val wsPath = RawWebSocketPath("/chat")
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
public class RawWebSocketPath<PATH : PathSpec> private constructor(
    public val pathSegments: PathSegments,
) : HasContextualPath<PATH> {
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
            // Not checked: a serialized path is trusted to be read back as the type it was written as.
            return RawWebSocketPath<T>(PathSegments.parse(decoder.decodeString()))
        }
    }

    private fun pinnedTo(match: PathSpecMap.Match<WebSocketHandler<PATH, *>>?): RawWebSocketPath<PATH> = apply {
        matchIfPresent = match
        searched = true
    }

    public companion object {
        @Unsafe("The path segments must route to a WebSocket endpoint of type PATH, or to nothing")
        public fun <PATH : PathSpec> fromPathSegments(pathSegments: PathSegments): RawWebSocketPath<PATH> =
            RawWebSocketPath<PATH>(pathSegments)

        /** A path that resolves to [match] without routing, at the path [match] was resolved from. */
        @Unsafe("The match must be for an endpoint of type PATH")
        context(server: Engine)
        public fun <PATH : PathSpec> fromMatch(
            match: PathSpecMap.Match<WebSocketHandler<PATH, *>>,
        ): RawWebSocketPath<PATH> = RawWebSocketPath<PATH>(match.path.pathSegments()).pinnedTo(match)

        /**
         * A path that resolves to nothing, even where a route would claim its segments.
         *
         * Only until serialized: a deserialized copy routes its segments like any other path.
         */
        @Unsafe("The path segments must route to a WebSocket endpoint of type PATH, or to nothing")
        public fun <PATH : PathSpec> unresolvable(pathSegments: PathSegments): RawWebSocketPath<PATH> =
            RawWebSocketPath<PATH>(pathSegments).pinnedTo(null)
    }
}

/** A path that has not been routed yet, so claims no particular [PathSpec]. */
public fun RawWebSocketPath(pathSegments: PathSegments): RawWebSocketPath<PathSpec> =
    @OptIn(Unsafe::class) // SAFETY: Every endpoint is a PathSpec
    RawWebSocketPath.fromPathSegments(pathSegments)

/** A path that has not been routed yet, so claims no particular [PathSpec]. */
public fun RawWebSocketPath(path: String): RawWebSocketPath<PathSpec> = RawWebSocketPath(PathSegments.parse(path))

/** Resolves only to the handler registered at [path]'s own spec, even if another route would claim its segments. */
@OptIn(Unsafe::class)
context(server: Engine)
public fun <PATH : PathSpec> RawWebSocketPath(path: ResolvedPath<PATH>): RawWebSocketPath<PATH> {
    // Pinned rather than routed: a more specific route (/users/me over /users/{id}) would otherwise
    // claim these segments under a different PathSpec than PATH.
    @Suppress("UNCHECKED_CAST")
    val handler = server.server.endpoints[path.pathSpec]?.webSocket as WebSocketHandler<PATH, *>?
    return if (handler != null) {
        // SAFETY: The match is for PATH's own spec, and handlers are registered under the spec they take
        RawWebSocketPath.fromMatch(PathSpecMap.Match(path, handler))
    } else {
        // SAFETY: Resolves to nothing here. A deserialized copy routes these segments again, trusting, as every
        // serialized path does, that no more specific route claims them.
        RawWebSocketPath.unresolvable(path.pathSegments())
    }
}

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