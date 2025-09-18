package com.lightningkite.lightningserver.cors

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.definition.CorsSettings
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpInterceptor
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketHandlerInterceptor

public class CorsInterceptor(public val cors: Runtime<CorsSettings>) : HttpInterceptor, WebSocketHandlerInterceptor {
    override val name: String get() = "CORS"

    context(runtime: ServerRuntime)
    override suspend fun handle(
        request: HttpRequest<*>,
        cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse
    ): HttpResponse {
        val origin = request.headers[HttpHeader.Origin]?.root ?: generalSettings().publicUrlDomain
        val matchingOrigin = cors().limitToDomains
            ?.plus(generalSettings().publicUrlDomain)
            ?.let { allowed ->
                if (originMatches(
                        allowed,
                        origin
                    )
                ) origin else throw BadRequestException("Origin $origin not in allowed domains $allowed")
            } ?: origin
        val baseResponse = cont(request)
        return baseResponse.copy(
            headers = baseResponse.headers + HttpHeaders {
                set(HttpHeader.AccessControlAllowOrigin, matchingOrigin)

                if (cors().allowCredentials == true)
                    set(HttpHeader.AccessControlAllowCredentials, "true")

                if (request.path.method == HttpMethod.OPTIONS) {
                    val allowedMethods = baseResponse.headers.getMany(HttpHeader.AccessControlAllowMethods).map { it.root }
                        .filter { cors().limitToMethods?.contains(it) ?: true }
                    // TODO: this needs to replace!
                    set(HttpHeader.AccessControlAllowMethods, allowedMethods.joinToString(","))
                    set(
                        key = HttpHeader.AccessControlAllowHeaders,
                        value = cors().limitToHeaders?.joinToString()
                            ?: request.headers[HttpHeader.AccessControlRequestHeaders]?.root
                            ?: "",
                    )
                } else {
                    cors().exposedHeaders?.takeUnless { it.isEmpty() }?.joinToString()?.let {
                        set(HttpHeader.AccessControlExposeHeaders, it)
                    }
                }
            }
        )
    }

    override fun <PATH : PathSpec, T> invoke(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> {
        return object: WebSocketHandler<PATH, T> by handler {
            context(serverRuntime: ServerRuntime)
            override suspend fun willConnect(request: WebSocketConnectRequest<PATH>): T {
                val origin = request.headers[HttpHeader.Origin]?.root ?: generalSettings().publicUrl.substringAfter("://").substringBefore("/")
                val matchingOrigin = cors().limitToDomains
                    ?.let { allowed ->
                        if (originMatches(
                                allowed,
                                origin
                            )
                        ) origin else throw BadRequestException("Origin $origin not in allowed domains $allowed")
                    } ?: origin
                return handler.willConnect(request)
            }
        }
    }

}