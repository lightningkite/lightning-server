package com.lightningkite.lightningserver.http

import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Represents a complete URL path with its segments and query parameters.
 *
 * This combines the path portion (e.g., `/users/123`) and query string (e.g., `?filter=active`)
 * into a single structure.
 *
 * Example:
 * ```kotlin
 * val parsed = PathAndParams.parse("/api/users/123?filter=active&sort=name")
 * println(parsed.pathSegments.segments) // ["api", "users", "123"]
 * println(parsed.queryParameters["filter"]) // "active"
 * ```
 *
 * @property pathSegments The URL path broken into segments
 * @property queryParameters The query string parameters
 */
@Serializable(PathAndParams.Serializer::class)
public data class PathAndParams(
    val pathSegments: PathSegments,
    val queryParameters: QueryParameters
) {
    override fun toString(): String =
        "$pathSegments${if (queryParameters.entries.isNotEmpty()) "?$queryParameters" else ""}"

    public companion object {
        /**
         * Parses a full URL path with optional query string.
         *
         * @param path The path string (e.g., "/api/users?filter=active")
         * @return The parsed PathAndParams
         */
        public fun parse(path: String): PathAndParams {
            val split = path.split("?")
            return PathAndParams(
                PathSegments.parse(split[0]),
                split.getOrNull(1)?.let { QueryParameters.parse(it) } ?: QueryParameters.EMPTY
            )
        }
    }

    private object Serializer: kotlinx.serialization.KSerializer<PathAndParams> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.lightningserver.http.PathAndParams", PrimitiveKind.STRING)
        override fun deserialize(decoder: Decoder): PathAndParams = parse(decoder.decodeString())
        override fun serialize(encoder: Encoder, value: PathAndParams) = encoder.encodeString(value.toString())
    }
}

/**
 * Represents URL path segments with URL encoding/decoding support.
 *
 * This is a lightweight value class that wraps a list of decoded path segments.
 * When converted to string, segments are URL-encoded and joined with slashes.
 *
 * This could represent a relative path, so leading slashes are stripped before parsing, but when converted to a string
 * there is no leading slash.  NOTE THIS BEHAVIOR.
 *
 * Example:
 * ```kotlin
 * val segments = PathSegments.parse("api/users/john%20doe")
 * println(segments.segments) // ["api", "users", "john doe"] (decoded)
 * println(segments.toString()) // "api/users/john%20doe" (encoded)
 * ```
 *
 * @property segments The list of URL-decoded path segments
 */
@Serializable(PathSegments.Serializer::class)
@JvmInline
public value class PathSegments(public val segments: List<String>): List<String> by segments {
    override fun toString(): String = segments.joinToString("/") {
        URLEncoder.encode(
            it,
            Charsets.UTF_8
        )
    }

    public companion object {
        /** An empty PathSegments with no segments. */
        public val EMPTY: PathSegments = PathSegments(emptyList())

        /**
         * Parses a URL path into segments, URL-decoding each segment.
         *
         * Leading slashes are stripped before splitting.
         * Trailing slashes are preserved as an empty string segment to support
         * correct trailing slash redirect logic.
         *
         * @param path The URL path (e.g., "/api/users/123")
         * @return The parsed PathSegments with decoded segment values
         */
        public fun parse(path: String): PathSegments = PathSegments(
            path.removePrefix("/").split("/").map { URLDecoder.decode(it, Charsets.UTF_8) }
        )
    }

    private object Serializer: kotlinx.serialization.KSerializer<PathSegments> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.lightningserver.http.PathSegments", PrimitiveKind.STRING)
        override fun deserialize(decoder: Decoder): PathSegments = parse(decoder.decodeString())
        override fun serialize(encoder: Encoder, value: PathSegments) = encoder.encodeString(value.toString())
    }
}

/**
 * Represents URL query parameters with URL encoding/decoding support.
 *
 * This is a lightweight value class that wraps a list of key-value pairs representing
 * query string parameters. Parameters are stored decoded internally and encoded when
 * converted to string.
 *
 * Example:
 * ```kotlin
 * val params = QueryParameters.parse("filter=active&name=john%20doe")
 * println(params["filter"]) // "active"
 * println(params["name"]) // "john doe" (decoded)
 * println(params.toString()) // "filter=active&name=john%20doe" (encoded)
 * ```
 *
 * Multiple parameters with the same key are supported (e.g., `tag=a&tag=b`).
 *
 * @property entries The list of URL-decoded key-value pairs
 */
@Serializable(QueryParameters.Serializer::class)
@JvmInline
public value class QueryParameters(public val entries: List<Pair<String, String>>): List<Pair<String, String>> by entries {

    /**
     * Gets the first value for the specified parameter key.
     *
     * Returns null if the parameter is not present.
     *
     * @param key The parameter name
     * @return The first value for this key, or null if not present
     */
    public operator fun get(key: String): String? = entries.firstOrNull { it.first == key }?.second

    // TODO: Remove this fugly hack and deal with websocket auth better
    public fun pathHack(): QueryParameters = QueryParameters(entries.flatMap {
        if (it.first == "path" && it.second.contains('?'))
            listOf(it) + parse(it.second.substringAfter('?')).entries
        else
            listOf(it)
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
        /** An empty QueryParameters with no parameters. */
        public val EMPTY: QueryParameters = QueryParameters(emptyList())

        /**
         * Parses a query string into parameters, URL-decoding keys and values.
         *
         * @param path The query string (e.g., "filter=active&sort=name")
         * @return The parsed QueryParameters with decoded keys and values
         */
        public fun parse(path: String): QueryParameters {
            return QueryParameters(
                path.split('&').filter { it.isNotBlank() }.map { it.split('=', limit = 2) }.map {
                    URLDecoder.decode(
                        it[0],
                        Charsets.UTF_8
                    ) to (it.getOrNull(1)?.let { URLDecoder.decode(it, Charsets.UTF_8) } ?: "")
                }
            )
        }
    }

    private object Serializer: kotlinx.serialization.KSerializer<QueryParameters> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.lightningserver.http.QueryParameters", PrimitiveKind.STRING)
        override fun deserialize(decoder: Decoder): QueryParameters = parse(decoder.decodeString())
        override fun serialize(encoder: Encoder, value: QueryParameters) = encoder.encodeString(value.toString())
    }
}

/*
 * TODO: API Recommendations and Issues for parse.kt
 *
 * 1. **ISSUE**: PathAndParams.parse() doesn't handle paths with multiple '?' correctly.
 *    Only splits on first '?' but subsequent '?' in query string will be kept as-is.
 *    This is probably fine but worth documenting.
 *
 * 2. **ISSUE**: PathSegments.parse() with an empty string results in a single empty segment [""]
 *    instead of an empty list. This is because "".split("/") returns [""].
 *    Consider: if (path.isEmpty()) return EMPTY
 *
 * 3. **ISSUE**: QueryParameters.parse() with an empty string results in one entry with empty key and value
 *    instead of EMPTY. Consider: if (path.isEmpty()) return EMPTY
 *
 * 4. **CRITICAL TODO**: The pathHack() function is marked as a "fugly hack" and should be removed.
 *    This appears to be a workaround for WebSocket authentication. Document the proper fix.
 *
 * 5. QueryParameters could benefit from a getAll(key: String): List<String> method
 *    for retrieving all values for a given key (e.g., multiple tags).
 *
 * 6. The parsing doesn't handle invalid URL encoding gracefully - URLDecoder.decode can throw
 *    IllegalArgumentException. Consider wrapping in try-catch or using Result type.
 *
 * 7. PathSegments removes leading slash but not trailing slash. A path like "/api/users/"
 *    will have an empty string as the last segment. Document or handle this behavior.
 *
 * 8. Consider adding a merge/combine method for QueryParameters to easily combine
 *    query strings from different sources.
 *
 * 9. The toString() implementation uses deprecated URLEncoder.encode(String, Charset).
 *    While it still works, consider updating to URLEncoder.encode(String, String) with "UTF-8".
 */
