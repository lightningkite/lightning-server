package com.lightningkite.lightningserver.http

@JvmInline
value class HttpMethod(private val asString: String) {
    companion object {
        val GET = HttpMethod("GET")
        val POST = HttpMethod("POST")
        val PUT = HttpMethod("PUT")
        val PATCH = HttpMethod("PATCH")
        val DELETE = HttpMethod("DELETE")
        val HEAD = HttpMethod("HEAD")
    }

    override fun toString(): String = asString
}