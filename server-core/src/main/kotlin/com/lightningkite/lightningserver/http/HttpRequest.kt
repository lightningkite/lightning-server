package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.settings.generalSettings

data class HttpRequest(
    /** The endpoint that is being responded to. **/
    val endpoint: HttpEndpoint,
    /** The values of wildcard path segments. **/
    override val parts: Map<String, String> = mapOf(),
    /** Any value filling {...}. **/
    override val wildcard: String? = null,
    /** Access to the query parameters (?param=value) **/
    override val queryParameters: List<Pair<String, String>> = listOf(),
    /** Access to any headers sent with the request **/
    override val headers: HttpHeaders = HttpHeaders.EMPTY,
    /** Access to the content of the request **/
    val body: HttpContent? = null,
    /** The domain used in making the request **/
    override val domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    /** The protocol used in making the request - HTTP or HTTPS **/
    override val protocol: String = generalSettings().publicUrl.substringBefore("://"),
    /** The originating public IP of the request, as can best be determined **/
    override val sourceIp: String = "0.0.0.0"
): Request {
    override val path: ServerPath
        get() = endpoint.path
    val method: HttpMethod
        get() = endpoint.method

    fun queryParameter(key: String): String? = queryParameters.find { it.first == key }?.second

    private val cache = HashMap<Request.CacheKey<*>, Any?>()
    override suspend fun <T> cache(key: Request.CacheKey<T>): T {
        @Suppress("UNCHECKED_CAST")
        if(cache.containsKey(key)) return cache[key] as T
        val calculated = key.calculate(this)
        cache[key] = calculated
        return calculated
    }
}

