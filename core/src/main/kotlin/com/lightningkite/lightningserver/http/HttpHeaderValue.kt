package com.lightningkite.lightningserver.http

import kotlinx.html.emptyMap

public data class HttpHeaderValue(
    val root: String,
    val parameters: Map<String, String>,
) {
    public companion object {
        private fun parse(raw: String): HttpHeaderValue = HttpHeaderValue(
            raw.substringBefore(';'),
            raw.substringAfter(';', "")
                .takeIf { it.isNotBlank() }
                ?.split(';')
                ?.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
                ?: emptyMap
        )

        private fun parseCookies(raw: String): HttpHeaderValue = HttpHeaderValue(
            root = "",
            parameters = raw.split(';')
                .associate { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }
        )

        public fun parse(header: String, raw: String): HttpHeaderValue =
            if (header.equals(HttpHeader.Cookie, ignoreCase = true)) parseCookies(raw)
            else if (header.equals(HttpHeader.SetCookie, ignoreCase = true)) parseCookies(raw)
            else parse(raw)
    }

    public fun toHttpString(): String =
        if (root.isEmpty()) (parameters.entries.takeUnless { it.isEmpty() }?.joinToString("; ") {
            if (it.value.isEmpty()) it.key
            else "${it.key}=${it.value}"
        } ?: "")
        else root + (parameters.entries.takeUnless { it.isEmpty() }?.joinToString("; ", "; ") {
            if (it.value.isEmpty()) it.key
            else "${it.key}=${it.value}"
        } ?: "")

    override fun toString(): String = toHttpString()
}