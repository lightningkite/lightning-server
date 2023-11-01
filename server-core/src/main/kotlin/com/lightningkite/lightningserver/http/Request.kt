package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.core.ServerPath

interface Request {
    val segments: List<String>
    val queryParameters: List<Pair<String, String>>
    val headers: HttpHeaders
    val domain: String
    val protocol: String
    val sourceIp: String

    interface CacheKey<T> {
        suspend fun calculate(request: Request): T
    }
    suspend fun <T> cache(key: CacheKey<T>): T
}