package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.PathSpec
import com.lightningkite.lightningserver.PathSpec1
import com.lightningkite.lightningserver.PathSpec2
import com.lightningkite.lightningserver.PathSpec3
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.settings.generalSettings

interface HttpRequest: Request {
    /** Access to the content of the request **/
    val body: HttpContent?
    /** The domain used in making the request **/
    override val domain: String
    /** The protocol used in making the request - HTTP or HTTPS **/
    override val protocol: String
    /** The originating public IP of the request, as can best be determined **/
    override val sourceIp: String

    fun queryParameter(key: String): String? = queryParameters.find { it.first == key }?.second

//    private val cache = HashMap<Request.CacheKey<*>, Any?>()
//    override suspend fun <T> cache(key: Request.CacheKey<T>): T {
//        @Suppress("UNCHECKED_CAST")
//        if(cache.containsKey(key)) return cache[key] as T
//        val calculated = key.calculate(this)
//        cache[key] = calculated
//        return calculated
//    }
}

interface HttpRequestWithPath<PATH: PathSpec>: HttpRequest {
    val pathSpec: PATH
    val rawPathArguments: Array<Any?>
}

@Suppress("UNCHECKED_CAST") val <A> HttpRequestWithPath<PathSpec1<A>>.first: A get() = rawPathArguments[0] as A
@Suppress("UNCHECKED_CAST") val <A, B> HttpRequestWithPath<PathSpec2<A, B>>.first: A get() = rawPathArguments[0] as A
@Suppress("UNCHECKED_CAST") val <A, B> HttpRequestWithPath<PathSpec2<A, B>>.second: B get() = rawPathArguments[1] as B
@Suppress("UNCHECKED_CAST") val <A, B, C> HttpRequestWithPath<PathSpec3<A, B, C>>.first: A get() = rawPathArguments[0] as A
@Suppress("UNCHECKED_CAST") val <A, B, C> HttpRequestWithPath<PathSpec3<A, B, C>>.second: B get() = rawPathArguments[1] as B
@Suppress("UNCHECKED_CAST") val <A, B, C> HttpRequestWithPath<PathSpec3<A, B, C>>.third: C get() = rawPathArguments[1] as C
