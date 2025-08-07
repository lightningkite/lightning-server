package com.lightningkite.lightningserver

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

public class PathSerializer<T: PathSpec>(ignored: KSerializer<T>) : KSerializer<PathServer<T>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.lightningserver.Path", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: PathServer<T>
    ) {
        encoder.encodeString(value.asString)
    }

    override fun deserialize(decoder: Decoder): PathServer<T> {
        return PathServer<T>(decoder.decodeString())
    }
}

@Serializable(with = PathSerializer::class)
public class PathServer<PATH: PathSpec>(
    public val asString: String,
): PathSpecResolvableInServerRunning<PATH> {

    public companion object {
        public operator fun invoke(spec: PathSpec0): PathServer<PathSpec0> = PathServer(spec.segments.joinToString("/"))
        context(serverRunning: ServerRunning)
        public operator fun <A> invoke(spec: PathSpec1<A>, path1: A): PathServer<PathSpec1<A>> = PathServer(spec.segments.joinToString("/") {
            when(it) {
                is PathSpec.Segment.Constant -> it.value
                is PathSpec.Segment.Wildcard<*> -> serverRunning.server.externalSerialization.stringArrayFormat.encodeToString(it.serializer as KSerializer<Any?>, path1)
            }
        })
        context(serverRunning: ServerRunning)
        public operator fun <A, B> invoke(spec: PathSpec2<A, B>, path1: A, path2: B): PathServer<PathSpec2<A, B>> {
            var i = 0
            return PathServer(spec.segments.joinToString("/") {
                when (it) {
                    is PathSpec.Segment.Constant -> it.value
                    is PathSpec.Segment.Wildcard<*> -> serverRunning.server.externalSerialization.stringArrayFormat.encodeToString(
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
        context(serverRunning: ServerRunning)
        public operator fun <A, B, C> invoke(spec: PathSpec3<A, B, C>, path1: A, path2: B, path3: C): PathServer<PathSpec3<A, B, C>> {
            var i = 0
            return PathServer(spec.segments.joinToString("/") {
                when (it) {
                    is PathSpec.Segment.Constant -> it.value
                    is PathSpec.Segment.Wildcard<*> -> serverRunning.server.externalSerialization.stringArrayFormat.encodeToString(
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
    context(server: ServerRunning)
    override val resolvable: PathSpecResolvable<PATH> get() = match as PathSpecResolvable<PATH>

    private var matchIfPresent: PathSpecMap.Match<ServerPathHandlers>? = null

    context(server: ServerRunning)
    public val match: PathSpecMap.Match<ServerPathHandlers> get() {
        if(this.matchIfPresent == null) {
            this.matchIfPresent =
                server.server.handlers.match(server.server.externalSerialization.stringArrayFormat, asString)
        }
        return this.matchIfPresent!!
    }


    public constructor(
        asString: String,
        match: PathSpecMap.Match<ServerPathHandlers>
    ) : this(asString) {
        this.matchIfPresent = match
    }

    override fun equals(other: Any?): Boolean = other is PathServer<*> && other.asString == asString
    override fun hashCode(): Int = asString.hashCode() + 1
    override fun toString(): String = asString
}