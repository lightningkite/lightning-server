package com.lightningkite.lightningserver.http

data class HttpContentAndHeaders(
    val headers: HttpHeaders,
    val content: HttpContent,
)