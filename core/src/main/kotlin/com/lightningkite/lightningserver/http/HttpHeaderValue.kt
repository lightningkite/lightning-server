package com.lightningkite.lightningserver.http

import kotlinx.html.emptyMap

/**
 * Represents a parsed HTTP header value with its root value and optional parameters.
 *
 * Many HTTP headers follow the pattern: `root; param1=value1; param2=value2`.
 * This class parses that structure. For example, Content-Type might be:
 * `text/html; charset=utf-8` where "text/html" is the root and {"charset": "utf-8"} are the parameters.
 *
 * Cookies are handled specially - they have no root value, only parameters.
 *
 * Example:
 * ```kotlin
 * val value = HttpHeaderValue.parse("Content-Type", "application/json; charset=utf-8")
 * println(value.root) // "application/json"
 * println(value.parameters["charset"]) // "utf-8"
 * ```
 *
 * @property root The primary value before any semicolons (empty for cookies)
 * @property parameters Map of parameter names to values from the header
 */
public data class HttpHeaderValue(
    val root: String,
    val parameters: Map<String, String>,
) {
    public companion object {
        /**
         * Parses a standard header value with the format: `value; param1=value1; param2=value2`.
         */
        private fun parse(raw: String): HttpHeaderValue = HttpHeaderValue(
            raw.substringBefore(';'),
            raw.substringAfter(';', "")
                .takeIf { it.isNotBlank() }
                ?.split(';')
                ?.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
                ?: emptyMap
        )

        /**
         * Parses cookie headers which don't have a root value, only name=value pairs.
         */
        private fun parseCookies(raw: String): HttpHeaderValue = HttpHeaderValue(
            root = "",
            parameters = raw.split(';')
                .associate { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }
        )

        /**
         * Parses a header value, using the appropriate parsing strategy based on the header name.
         *
         * Cookie and Set-Cookie headers are parsed differently than other headers since they
         * consist only of name=value pairs without a root value.
         *
         * @param header The header name (used to determine parsing strategy)
         * @param raw The raw header value string
         * @return The parsed HttpHeaderValue
         */
        public fun parse(header: String, raw: String): HttpHeaderValue =
            if (header.equals(HttpHeader.Cookie, ignoreCase = true)) parseCookies(raw)
            else if (header.equals(HttpHeader.SetCookie, ignoreCase = true)) parseCookies(raw)
            else parse(raw)
    }

    /**
     * Converts this header value back to its HTTP string representation.
     *
     * For headers with a root value: `root; param1=value1; param2=value2`
     * For cookies (no root): `param1=value1; param2=value2`
     *
     * Parameters with empty values are rendered as just the key name.
     *
     * @return The formatted HTTP header value string
     */
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

/*
 * TODO: API Recommendations for HttpHeaderValue.kt
 *
 * 1. The parsing doesn't handle quoted values in parameters (common in headers like Content-Disposition).
 *    For example: filename="file; with; semicolons.txt" would be incorrectly parsed.
 *    Consider supporting RFC 2616 quoted-string format.
 *
 * 2. The parseCookies function may not correctly handle cookies without values (just the name).
 *    substringAfter('=', "") returns empty string but should probably check if '=' exists.
 *
 * 3. Parameter names and values should preserve case sensitivity, but the implementation
 *    doesn't document this. Some headers have case-sensitive parameters.
 *
 * 4. The parse() method could fail silently on malformed input. Consider throwing
 *    an exception or returning a Result type for invalid header values.
 *
 * 5. Add convenience methods for common parameter access patterns:
 *    - fun getParameter(name: String, ignoreCase: Boolean = false): String?
 *    - fun hasParameter(name: String): Boolean
 *
 * 6. The emptyMap import from kotlinx.html is unusual - should use kotlin.collections.emptyMap()
 */