package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.ServerPathEndpoints
import com.lightningkite.lightningserver.ServerRuntime
import com.lightningkite.lightningserver.ServerPathHandlers
import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

public class PathSerializer<T: PathSpec>(ignored: KSerializer<T>) : KSerializer<ServerPath<T>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.lightningserver.Path", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: ServerPath<T>
    ) {
        encoder.encodeString(value.asString)
    }

    override fun deserialize(decoder: Decoder): ServerPath<T> {
        return ServerPath<T>(decoder.decodeString())
    }
}

@Serializable(with = PathSerializer::class)
public class ServerPath<PATH: PathSpec>(
    public val asString: String,
): HasContextualPath<PATH> {

    public companion object {
        public operator fun invoke(spec: PathSpec0): ServerPath<PathSpec0> = ServerPath(spec.segments.joinToString("/"))
        context(serverRuntime: ServerRuntime)
        public operator fun <A> invoke(spec: PathSpec1<A>, path1: A): ServerPath<PathSpec1<A>> = ServerPath(spec.segments.joinToString("/") {
            when(it) {
                is PathSpec.Segment.Constant -> it.value
                is PathSpec.Segment.Wildcard<*> -> serverRuntime.externalSerialization.stringArrayFormat.encodeToString(it.serializer as KSerializer<Any?>, path1)
            }
        })
        context(serverRuntime: ServerRuntime)
        public operator fun <A, B> invoke(spec: PathSpec2<A, B>, path1: A, path2: B): ServerPath<PathSpec2<A, B>> {
            var i = 0
            return ServerPath(spec.segments.joinToString("/") {
                when (it) {
                    is PathSpec.Segment.Constant -> it.value
                    is PathSpec.Segment.Wildcard<*> -> serverRuntime.externalSerialization.stringArrayFormat.encodeToString(
                        it.serializer as KSerializer<Any?>,
                        when(i++) {
                            0 -> path1
                            1 -> path2
                            else -> throw Error("This should never be reachable")
                        }
                    )
                }
            })
        }
        context(serverRuntime: ServerRuntime)
        public operator fun <A, B, C> invoke(spec: PathSpec3<A, B, C>, path1: A, path2: B, path3: C): ServerPath<PathSpec3<A, B, C>> {
            var i = 0
            return ServerPath(spec.segments.joinToString("/") {
                when (it) {
                    is PathSpec.Segment.Constant -> it.value
                    is PathSpec.Segment.Wildcard<*> -> serverRuntime.externalSerialization.stringArrayFormat.encodeToString(
                        it.serializer as KSerializer<Any?>,
                        when(i++) {
                            0 -> path1
                            1 -> path2
                            2 -> path3
                            else -> throw Error("This should never be reachable")
                        }
                    )
                }
            })
        }
    }

    @Suppress("UNCHECKED_CAST")
    context(server: ServerRuntime)
    override val pathInContext: ConcretePath<PATH> get() = match as ConcretePath<PATH>

    private var matchIfPresent: PathSpecMap.Match<ServerPathEndpoints>? = null

    context(server: ServerRuntime)
    public val match: PathSpecMap.Match<ServerPathEndpoints> get() {
        if(this.matchIfPresent == null) {
            this.matchIfPresent =
                server.server.endpoints.match(server.externalSerialization.stringArrayFormat, asString)
        }
        return this.matchIfPresent!!
    }


    public constructor(
        asString: String,
        match: PathSpecMap.Match<ServerPathEndpoints>
    ) : this(asString) {
        this.matchIfPresent = match
    }

    override fun equals(other: Any?): Boolean = other is ServerPath<*> && other.asString == asString
    override fun hashCode(): Int = asString.hashCode() + 1
    override fun toString(): String = asString
}