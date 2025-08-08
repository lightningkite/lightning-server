package com.lightningkite.lightningserver.http

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.HttpHeaderValue
import kotlinx.serialization.Serializable
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.String
import kotlin.collections.Map
import kotlin.time.Instant
import kotlin.time.toJavaInstant

@Serializable
public class HttpHeaders internal constructor (internal val normalizedEntries: Map<String, List<HttpHeaderValue>>) {
    override fun equals(other: Any?): Boolean = other is HttpHeaders && other.normalizedEntries == this.normalizedEntries
    override fun hashCode(): Int = normalizedEntries.hashCode() + 1
    override fun toString(): String = normalizedEntries.toString()
    public companion object {
        public val EMPTY: HttpHeaders = HttpHeaders(mapOf<String, List<HttpHeaderValue>>())
    }

    public operator fun get(key: String): HttpHeaderValue? = normalizedEntries[key.lowercase()]?.firstOrNull()
    public fun getMany(key: String): List<HttpHeaderValue> = normalizedEntries[key.lowercase()] ?: listOf()

    public operator fun plus(other: HttpHeaders): HttpHeaders = HttpHeaders(
        (normalizedEntries.keys + other.normalizedEntries.keys).associateWith<String, List<HttpHeaderValue>> {
            (normalizedEntries[it] ?: listOf()) + (other.normalizedEntries[it] ?: listOf())
        }
    )

    public val cookies: Map<String, String> by lazy {
        this[HttpHeader.Cookie]?.parameters ?: mapOf()
    }

    public val contentType: MediaType? by lazy {
        this[HttpHeader.ContentType]?.let {
            MediaType(
                it.root.substringBefore('/').trim(),
                it.root.substringAfter('/').trim(),
                it.parameters
            )
        }
    }
    public val contentLength: Long? by lazy {
        this[HttpHeader.ContentLength]?.root?.toLongOrNull()
    }
    public val accept: List<MediaType> by lazy {
        this.getMany(HttpHeader.Accept).map { MediaType(it.root) }
    }

    public enum class SameSite {
        Strict, Lax, None
    }

    public fun copy(builder: Builder.()->Unit): HttpHeaders = Builder().also { it.set(this) }.apply(builder).build()

    public class Builder() {
        private val entries = HashMap<String, ArrayList<HttpHeaderValue>>()
        public fun set(key: String, value: String) {
            entries.getOrPut(key.lowercase()) { ArrayList() }.add(HttpHeaderValue.Companion.parse(value))
        }

        public fun set(key: String, value: HttpHeaderValue) {
            entries.getOrPut(key.lowercase()) { ArrayList() }.add(value)
        }

        public fun set(headers: HttpHeaders) {
            headers.normalizedEntries.forEach { (key, values) ->
                entries.getOrPut(key) { ArrayList() }.addAll(values)
            }
        }

        public fun setCookie(
            key: String,
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
            set("Set-Cookie", HttpHeaderValue(
                root = "",
                parameters = buildMap {
                    put("key", key)
                    put("value", value)
                    if (expiresAt != null) {
                        put("expiresAt", DateTimeFormatter.RFC_1123_DATE_TIME.format(expiresAt.toJavaInstant().atOffset(ZoneOffset.UTC)))
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

        public fun build(): HttpHeaders = HttpHeaders(entries)
    }
}

public fun HttpHeaders(vararg entry: Pair<String, String>): HttpHeaders = HttpHeaders(entry.groupBy { it.first.lowercase() }.mapValues { it.value.map { HttpHeaderValue.Companion.parse(it.second) }})
public inline fun HttpHeaders(setup: HttpHeaders.Builder.() -> Unit): HttpHeaders = HttpHeaders.Builder().apply(setup).build()