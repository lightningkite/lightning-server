package com.lightningkite.lightningserver.http

import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.function.IntFunction

@Serializable(PathAndParams.MySerializer::class)
public data class PathAndParams(
    val pathSegments: PathSegments,
    val queryParameters: QueryParameters
) {
    override fun toString(): String =
        "$pathSegments${if (queryParameters.entries.isNotEmpty()) "?$queryParameters" else ""}"

    public companion object {
        public fun parse(path: String): PathAndParams {
            val split = path.split("?")
            return PathAndParams(
                PathSegments.parse(split[0]),
                split.getOrNull(1)?.let { QueryParameters.parse(it) } ?: QueryParameters.EMPTY
            )
        }
    }

    internal class MySerializer: kotlinx.serialization.KSerializer<PathAndParams> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.lightningserver.http.PathAndParams", PrimitiveKind.STRING)
        override fun deserialize(decoder: Decoder): PathAndParams = parse(decoder.decodeString())
        override fun serialize(encoder: Encoder, value: PathAndParams) = encoder.encodeString(value.toString())
    }
}

@Serializable(PathSegments.MySerializer::class)
@JvmInline
public value class PathSegments(public val segments: List<String>): List<String> by segments {
    override fun toString(): String = segments.joinToString("/") {
        URLEncoder.encode(
            it,
            Charsets.UTF_8
        )
    }
    public companion object {
        public val EMPTY: PathSegments = PathSegments(listOf())
        public fun parse(path: String): PathSegments = PathSegments(path.removePrefix("/").split("/").map { URLDecoder.decode(it, Charsets.UTF_8) })
    }

    internal class MySerializer: kotlinx.serialization.KSerializer<PathSegments> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.lightningserver.http.PathSegments", PrimitiveKind.STRING)
        override fun deserialize(decoder: Decoder): PathSegments = parse(decoder.decodeString())
        override fun serialize(encoder: Encoder, value: PathSegments) = encoder.encodeString(value.toString())
    }
}

@Serializable(QueryParameters.MySerializer::class)
@JvmInline
public value class QueryParameters(public val entries: List<Pair<String, String>>): List<Pair<String, String>> by entries {

    public operator fun get(key: String): String? = entries.firstOrNull { it.first == key }?.second

    // TODO: Remove this fugly hack and deal with websocket auth better
    public fun pathHack(): QueryParameters = QueryParameters(entries.flatMap {
        if (it.first == "path") listOf(it) + parse(it.second)
        else listOf(it)
    })

    override fun toString(): String = entries.joinToString("&") {
        "${
            URLEncoder.encode(
                it.first,
                Charsets.UTF_8
            )
        }=${URLEncoder.encode(it.second, Charsets.UTF_8)}"
    }

    public companion object {
        public val EMPTY: QueryParameters = QueryParameters(listOf())
        public fun parse(path: String): QueryParameters {
            return QueryParameters(
                path.split('&').map { it.split('=', limit = 2) }.map {
                    URLDecoder.decode(
                        it[0],
                        Charsets.UTF_8
                    ) to URLDecoder.decode(it[1], Charsets.UTF_8)
                }
            )
        }
    }

    internal class MySerializer: kotlinx.serialization.KSerializer<QueryParameters> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.lightningserver.http.QueryParameters", PrimitiveKind.STRING)
        override fun deserialize(decoder: Decoder): QueryParameters = parse(decoder.decodeString())
        override fun serialize(encoder: Encoder, value: QueryParameters) = encoder.encodeString(value.toString())
    }
}