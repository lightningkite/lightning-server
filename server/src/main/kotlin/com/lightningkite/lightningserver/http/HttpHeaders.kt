package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.core.ContentType
import java.time.Instant
import java.time.format.DateTimeFormatter

data class HttpHeaders(val entries: List<Pair<String, String>>) {
    val normalizedEntries: Map<String, List<String>> = entries
        .groupBy { it.first.lowercase() }
        .mapValues { it.value.map { it.second } }

    companion object {
        val EMPTY = HttpHeaders(listOf())
    }

    operator fun get(key: String): String? = normalizedEntries[key.lowercase()]?.firstOrNull()

    val cookies: Map<String, String> by lazy {
        this[HttpHeader.Cookie]?.let {
            it.split(';')
                .map { it.trim() }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
        } ?: mapOf()
    }

    val contentType: ContentType? by lazy {
        this[HttpHeader.ContentType]?.let {
            ContentType(it)
        }
    }
    val contentLength: Long? by lazy {
        this[HttpHeader.ContentLength]?.toLongOrNull()
    }
    val accept: List<ContentType> by lazy {
        this[HttpHeader.Accept]?.let {
            it.split(',').map { ContentType(it.trim()) }
        } ?: listOf()
    }

    enum class SameSite {
        Strict, Lax, None
    }

    class Builder() {
        val entries = ArrayList<Pair<String, String>>()
        fun set(key: String, value: String) {
            entries.add(key to value)
        }

        fun setCookie(
            key: String,
            value: String,
            expiresAt: Instant? = null,
            maxAge: Int? = null,
            domain: String? = null,
            path: String? = "/",
            secure: Boolean = false,
            httpOnly: Boolean = false,
            sameSite: SameSite? = null
        ) {
            entries.add("Set-Cookie" to buildString {
                append("$key=$value")
                if (expiresAt != null) {
                    append("; Expires=")
                    append(DateTimeFormatter.RFC_1123_DATE_TIME.format(expiresAt))
                }
                if (maxAge != null) {
                    append("; Max-Age=")
                    append(maxAge)
                }
                if (domain != null) {
                    append("; Domain=")
                    append(domain)
                }
                if (path != null) {
                    append("; Path=")
                    append(path)
                }
                if (secure) {
                    append("; Secure")
                }
                if (httpOnly) {
                    append("; HttpOnly")
                }
                if (sameSite != null) {
                    append("; SameSite=")
                    append(sameSite)
                }
            })
        }

        fun build(): HttpHeaders = HttpHeaders(entries)
    }
}

fun HttpHeaders(entries: Map<String, String>) = HttpHeaders(entries.entries.map { it.toPair() })
fun HttpHeaders(vararg entry: Pair<String, String>) = HttpHeaders(mapOf(*entry))