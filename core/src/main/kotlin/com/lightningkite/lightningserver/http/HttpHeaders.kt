package com.lightningkite.lightningserver.http

import com.lightningkite.services.data.MediaType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * Represents HTTP headers as a collection of header name-value pairs.
 *
 * Header names are case-insensitive and automatically normalized to lowercase internally.
 * Multiple values for the same header are supported.
 *
 * Example:
 * ```kotlin
 * val headers = HttpHeaders {
 *     set("Content-Type", "application/json")
 *     set("X-Custom-Header", "value")
 *     setCookie("session", "abc123", httpOnly = true, secure = true)
 * }
 *
 * val contentType = headers[HttpHeader.ContentType] // Access by key
 * val allAccept = headers.getMany(HttpHeader.Accept) // Multiple values
 * ```
 *
 * Provides convenience accessors for common headers like `contentType`, `contentLength`,
 * `accept`, and `cookies`.
 *
 * @property normalizedEntries Internal map of lowercase header names to their values
 */
@Serializable(HttpHeadersSerializer::class)
public class HttpHeaders internal constructor(public val normalizedEntries: Map<String, List<HttpHeaderValue>>) {
    override fun equals(other: Any?): Boolean =
        other is HttpHeaders && other.normalizedEntries == this.normalizedEntries

    override fun hashCode(): Int = normalizedEntries.hashCode() + 1
    override fun toString(): String = normalizedEntries.toString()

    public companion object {
        /** An empty HttpHeaders instance with no headers. */
        public val EMPTY: HttpHeaders = HttpHeaders(mapOf<String, List<HttpHeaderValue>>())
    }

    /** Returns true if no headers are present. */
    public fun isEmpty(): Boolean = normalizedEntries.isEmpty()

    /** Returns true if at least one header is present. */
    public fun isNotEmpty(): Boolean = normalizedEntries.isNotEmpty()

    /**
     * Gets the first value for the specified header name.
     *
     * Header lookup is case-insensitive. Returns null if the header is not present.
     *
     * @param key The header name (case-insensitive)
     * @return The first header value, or null if not present
     */
    public operator fun get(key: String): HttpHeaderValue? = normalizedEntries[key.lowercase()]?.firstOrNull()

    /**
     * Gets all values for the specified header name.
     *
     * Some headers (like Accept, Set-Cookie) can appear multiple times.
     * Header lookup is case-insensitive.
     *
     * @param key The header name (case-insensitive)
     * @return A list of all values for this header (empty if not present)
     */
    public fun getMany(key: String): List<HttpHeaderValue> = normalizedEntries[key.lowercase()] ?: listOf()

    /**
     * Combines two HttpHeaders instances, merging their header values.
     *
     * If both headers contain the same header name, all values from both are retained.
     *
     * @param other The headers to merge with this instance
     * @return A new HttpHeaders containing all headers from both instances
     */
    public operator fun plus(other: HttpHeaders): HttpHeaders = HttpHeaders(
        (normalizedEntries.keys + other.normalizedEntries.keys).associateWith<String, List<HttpHeaderValue>> {
            (normalizedEntries[it] ?: listOf()) + (other.normalizedEntries[it] ?: listOf())
        }
    )

    /**
     * Parses and returns all cookies from the Cookie header as a map.
     *
     * This is a lazy property computed only when first accessed.
     *
     * @return Map of cookie names to their values
     */
    public val cookies: Map<String, String> by lazy {
        this[HttpHeader.Cookie]?.parameters ?: mapOf()
    }

    /**
     * Parses and returns the Content-Type header as a MediaType.
     *
     * Returns null if no Content-Type header is present.
     * This is a lazy property computed only when first accessed.
     *
     * @return The parsed MediaType, or null if not present
     */
    public val contentType: MediaType? by lazy {
        this[HttpHeader.ContentType]?.let {
            MediaType(
                it.root.substringBefore('/').trim(),
                it.root.substringAfter('/').trim(),
                it.parameters
            )
        }
    }

    /**
     * Parses and returns the Content-Length header as a Long.
     *
     * Returns null if the header is not present or cannot be parsed as a number.
     * This is a lazy property computed only when first accessed.
     *
     * @return The content length in bytes, or null if not present/invalid
     */
    public val contentLength: Long? by lazy {
        this[HttpHeader.ContentLength]?.root?.toLongOrNull()
    }

    /**
     * Parses and returns all Accept header values as a list of MediaTypes.
     *
     * This is a lazy property computed only when first accessed.
     *
     * @return List of accepted media types (empty if no Accept header)
     */
    public val accept: List<MediaType> by lazy {
        this.getMany(HttpHeader.Accept).map { MediaType(it.root) }
    }

    /**
     * SameSite cookie attribute values as defined in RFC 6265bis.
     *
     * - **Strict**: Cookie is only sent in first-party context
     * - **Lax**: Cookie is sent with top-level navigations and same-site requests
     * - **None**: Cookie is sent in all contexts (requires Secure attribute)
     */
    public enum class SameSite {
        Strict, Lax, None
    }

    /**
     * Creates a modified copy of these headers using a builder.
     *
     * Example:
     * ```kotlin
     * val newHeaders = headers.copy {
     *     set("X-Additional", "value")
     * }
     * ```
     *
     * @param builder Lambda to modify the headers
     * @return A new HttpHeaders instance with the modifications applied
     */
    public fun copy(builder: Builder.() -> Unit): HttpHeaders = Builder(this).apply(builder).build()

    /**
     * Builder for constructing HttpHeaders instances.
     *
     * Provides a mutable API for adding headers before creating an immutable HttpHeaders.
     *
     * Example:
     * ```kotlin
     * val headers = HttpHeaders.Builder()
     *     .apply {
     *         set("Content-Type", "application/json")
     *         setCookie("session", "token", httpOnly = true)
     *     }
     *     .build()
     * ```
     *
     * @param start Optional initial headers to copy from
     */
    public class Builder(start: HttpHeaders? = null) {
        private val entries = HashMap<String, ArrayList<HttpHeaderValue>>()

        /**
         * Adds a header with the given key and string value.
         *
         * Multiple calls with the same key will result in multiple header values.
         *
         * @param key The header name (will be normalized to lowercase)
         * @param value The header value as a string
         */
        public fun add(key: String, value: String) {
            entries.getOrPut(key.lowercase(), ::ArrayList).add(HttpHeaderValue.parse(key, value))
        }

        /**
         * Adds a header with the given key and parsed HttpHeaderValue.
         *
         * @param key The header name (will be normalized to lowercase)
         * @param value The pre-parsed header value
         */
        public fun add(key: String, value: HttpHeaderValue) {
            entries.getOrPut(key.lowercase(), ::ArrayList).add(value)
        }

        /**
         * Adds multiple values for the same header key.
         *
         * @param key The header name (will be normalized to lowercase)
         * @param values List of header values to add
         */
        public fun add(key: String, values: List<HttpHeaderValue>) {
            entries.getOrPut(key.lowercase(), ::ArrayList).addAll(values)
        }

        /**
         * Adds all headers from another HttpHeaders instance.
         *
         * @param headers The headers to copy from
         */
        public fun add(headers: HttpHeaders) {
            headers.normalizedEntries.forEach { (key, values) ->
                entries.getOrPut(key, ::ArrayList).addAll(values)
            }
        }

        /**
         * Remove a header from the builder
         */
        public fun remove(key: String) {
            entries.remove(key.lowercase())
        }

        init {
            start?.let(::add)
        }

        /**
         * Sets a cookie in the response headers.
         *
         * This generates a properly formatted Set-Cookie header with all the specified attributes.
         *
         * Example:
         * ```kotlin
         * setCookie(
         *     key = "session",
         *     value = "abc123",
         *     maxAge = 3600,
         *     path = "/",
         *     secure = true,
         *     httpOnly = true,
         *     sameSite = SameSite.Strict
         * )
         * ```
         *
         * @param name The cookie name
         * @param value The cookie value
         * @param expiresAt Optional expiration time (absolute)
         * @param maxAge Optional max age in seconds (relative)
         * @param domain Optional domain for the cookie
         * @param path Optional path for the cookie (default: "/")
         * @param secure If true, cookie is only sent over HTTPS
         * @param httpOnly If true, cookie is not accessible via JavaScript
         * @param sameSite Optional SameSite attribute for CSRF protection
         * @param extensions Optional additional cookie attributes
         */
        public fun setCookie(
            name: String,
            value: String,
            expiresAt: Instant? = null,
            maxAge: Int? = null,
            domain: String? = null,
            path: String? = "/",
            secure: Boolean = false,
            httpOnly: Boolean = false,
            sameSite: SameSite? = null,
            extensions: Map<String, String?> = emptyMap(),
        ) {
            add(
                HttpHeader.SetCookie, HttpHeaderValue(
                root = "",
                parameters = buildMap {
                    put("key", name)
                    put("value", value)
                    if (expiresAt != null) {
                        put(
                            "expiresAt",
                            DateTimeFormatter.RFC_1123_DATE_TIME.format(
                                expiresAt.toJavaInstant().atOffset(ZoneOffset.UTC)
                            )
                        )
                    }
                    if (maxAge != null) {
                        put("maxAge", maxAge.toString())
                    }
                    if (domain != null) {
                        put("domain", domain)
                    }
                    if (path != null) {
                        put("path", path)
                    }
                    if (secure) {
                        put("secure", "")
                    }
                    if (httpOnly) {
                        put("httpOnly", "")
                    }
                    if (sameSite != null) {
                        put("sameSite", sameSite.name)
                    }
                    extensions.forEach {
                        if (it.value != null) {
                            put(it.key, it.value!!)
                        } else {
                            put(it.key, "")
                        }
                    }
                }
            ))
        }

        /**
         * Builds an immutable HttpHeaders instance from the accumulated headers.
         *
         * @return The constructed HttpHeaders
         */
        public fun build(): HttpHeaders = HttpHeaders(entries)
    }
}

/**
 * Creates HttpHeaders from an iterable of key-value pairs.
 *
 * @param entries Iterable of header name to value pairs
 * @return The constructed HttpHeaders
 */
public fun HttpHeaders(entries: Iterable<Pair<String, String>>): HttpHeaders = HttpHeaders(
    entries
        .groupBy { it.first.lowercase() }
        .mapValues { (key, value) ->
            value
                .flatMap { it.second.split(',') }
                .map { HttpHeaderValue.parse(key, it.trim()) }
        }
)

/**
 * Creates HttpHeaders from vararg key-value pairs.
 *
 * Example:
 * ```kotlin
 * val headers = HttpHeaders(
 *     "Content-Type" to "application/json",
 *     "X-Custom" to "value"
 * )
 * ```
 *
 * @param entries Variable number of header name to value pairs
 * @return The constructed HttpHeaders
 */
public fun HttpHeaders(vararg entries: Pair<String, String>): HttpHeaders = HttpHeaders(entries.toList())

/**
 * Creates HttpHeaders using a builder DSL.
 *
 * This is the recommended way to construct headers.
 *
 * Example:
 * ```kotlin
 * val headers = HttpHeaders {
 *     set("Content-Type", "application/json")
 *     setCookie("session", "token")
 * }
 * ```
 *
 * @param setup Builder configuration lambda
 * @return The constructed HttpHeaders
 */
public inline fun HttpHeaders(setup: HttpHeaders.Builder.() -> Unit): HttpHeaders =
    HttpHeaders.Builder().apply(setup).build()

/**
 * Internal serializer for HttpHeaders.
 *
 * Serializes as a simple string map where values are comma-separated for multi-value headers.
 */
internal object HttpHeadersSerializer : KSerializer<HttpHeaders> {
    private val defer = MapSerializer(String.serializer(), String.serializer())

    override val descriptor: SerialDescriptor get() = defer.descriptor

    private fun HttpHeaders.toStringMap() = normalizedEntries.mapValues { entry ->
        entry.value.joinToString { it.toHttpString() }
    }

    override fun serialize(
        encoder: Encoder,
        value: HttpHeaders,
    ) {
        defer.serialize(encoder, value.toStringMap())
    }

    override fun deserialize(decoder: Decoder): HttpHeaders = HttpHeaders(
        defer.deserialize(decoder).flatMap { entry ->
            entry.value.split(',').map { entry.key to it }
        }
    )
}

/*
 * TODO: API Improvement Recommendations for HttpHeaders.kt:
 *
 * 1. HttpHeaders - Add typed accessors for common headers
 *    - Currently users must manually parse most headers
 *    - Consider adding: authorization, userAgent, referer, etc. as properties like contentType
 *    - Example: val authorization: String? - parsed from Authorization header
 *
 * 4. HttpHeaders.cookies - Inconsistent with setCookie
 *    - cookies returns Map<String, String> for reading Cookie header
 *    - setCookie sets Set-Cookie header with different structure
 *    - Consider adding setResponseCookie and getCookies/getResponseCookies for clarity
 */