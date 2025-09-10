package com.lightningkite.lightningserver

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@JvmInline
@Serializable
public value class HttpMethod(private val asString: String) {
    public companion object {
        public val GET: HttpMethod = HttpMethod("GET")
        public val POST: HttpMethod = HttpMethod("POST")
        public val PUT: HttpMethod = HttpMethod("PUT")
        public val PATCH: HttpMethod = HttpMethod("PATCH")
        public val DELETE: HttpMethod = HttpMethod("DELETE")
        public val OPTIONS: HttpMethod = HttpMethod("OPTIONS")
        public val HEAD: HttpMethod = HttpMethod("HEAD")
        public val WEBSOCKET: HttpMethod = HttpMethod("WEBSOCKET")
    }

    override fun toString(): String = asString
}