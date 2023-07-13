package com.lightningkite.lightningserver.http

data class HttpHeaderValue(
    val root: String,
    val parameters: Map<String, String>
) {
    constructor(raw: String):this(
        raw.substringBefore(';'),
        raw.substringAfter(';').split(';').associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    )

    override fun toString(): String = root + (parameters.entries.takeUnless { it.isEmpty() }?.joinToString("; ") {
        "${it.key}=${it.value}"
    } ?: "")
}