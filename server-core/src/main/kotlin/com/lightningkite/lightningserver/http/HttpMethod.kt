package com.lightningkite.lightningserver.http

import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer

@JvmInline
@Serializable
value class HttpMethod(private val asString: String) {
    companion object {
        val GET = HttpMethod("GET")
        val POST = HttpMethod("POST")
        val PUT = HttpMethod("PUT")
        val PATCH = HttpMethod("PATCH")
        val DELETE = HttpMethod("DELETE")
        val OPTIONS = HttpMethod("OPTIONS")
        val HEAD = HttpMethod("HEAD")
        val WEBSOCKET = HttpMethod("WEBSOCKET")
    }

    override fun toString(): String = asString
}