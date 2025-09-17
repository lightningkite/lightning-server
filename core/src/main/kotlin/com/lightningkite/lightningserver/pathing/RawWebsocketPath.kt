package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

private class PathSerializer<T: PathSpec>(ignored: KSerializer<T>) : KSerializer<RawWebsocketPath<T>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.lightningserver.Path", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: RawWebsocketPath<T>
    ) {
        encoder.encodeString(value.string)
    }

    override fun deserialize(decoder: Decoder): RawWebsocketPath<T> {
        return RawWebsocketPath<T>(decoder.decodeString())
    }
}

@Serializable(with = PathSerializer::class)
public class RawWebsocketPath<PATH: PathSpec>(public val string: String): HasContextualPath<PATH> {
    // TODO: This is fundamentally flawed; we can't find a match after the fact because we need to know what we're matching on.
    // This fucked up fact means that the entirety of this shit needs to change.
    // We can't use RawWebsocketPath to implement HasContextualPath because we can't know what the context is until we know what the path is.'

    @Suppress("UNCHECKED_CAST")
    context(server: ServerRuntime)
    override val pathInContext: ConcretePath<PATH> get() = match.path as ConcretePath<PATH>

    private var matchIfPresent: PathSpecMap.Match<*>? = null

    context(server: ServerRuntime)
    public val match: PathSpecMap.Match<*> get() {
        if (this.matchIfPresent == null) {
            this.matchIfPresent = server.server.endpoints.match(server.externalSerialization.stringArrayFormat, string) { it.websocket }
        }
        return this.matchIfPresent ?: throw NullPointerException("No match for path: $string. Registered paths are ${server.server.endpoints.keys}")
    }

    public constructor(
        asString: String,
        match: PathSpecMap.Match<*>
    ) : this(asString) {
        this.matchIfPresent = match
    }

    override fun equals(other: Any?): Boolean = other is RawWebsocketPath<*> && other.string == string
    override fun hashCode(): Int = string.hashCode() + 1
    override fun toString(): String = string
}

context(server: ServerRuntime)
public fun <PATH : PathSpec> RawWebsocketPath(path: ConcretePath<PATH>): RawWebsocketPath<PATH> = RawWebsocketPath(path.path(server.internalSerialization.stringArrayFormat))

context(serverRuntime: ServerRuntime)
public fun RawWebsocketPath(spec: PathSpec0, trailingSegments: ConcretePath.TrailingSegments? = null): RawWebsocketPath<PathSpec0> = RawWebsocketPath(ConcretePath(spec, trailingSegments))

context(serverRuntime: ServerRuntime)
public fun <A> RawWebsocketPath(spec: PathSpec1<A>, path1: A, trailingSegments: ConcretePath.TrailingSegments? = null): RawWebsocketPath<PathSpec1<A>> =
    RawWebsocketPath(ConcretePath(spec, path1, trailingSegments))

context(serverRuntime: ServerRuntime)
public fun <A, B> RawWebsocketPath(spec: PathSpec2<A, B>, path1: A, path2: B, trailingSegments: ConcretePath.TrailingSegments? = null): RawWebsocketPath<PathSpec2<A, B>> =
    RawWebsocketPath(ConcretePath(spec, path1, path2, trailingSegments))

context(serverRuntime: ServerRuntime)
public fun <A, B, C> RawWebsocketPath(spec: PathSpec3<A, B, C>, path1: A, path2: B, path3: C, trailingSegments: ConcretePath.TrailingSegments? = null): RawWebsocketPath<PathSpec3<A, B, C>> =
    RawWebsocketPath(ConcretePath(spec, path1, path2, path3, trailingSegments))