package com.lightningkite.lightningserver.cors

import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketHandlerInterceptor

internal fun originMatches(allowed: List<String>, origin: String): Boolean {
    val originSchema = origin.substringBefore("://")
    val originTrimmed = origin.substringAfter("://")
    return allowed
        .any {
            val allowedSchema = it.substringBefore("://", "")
            val allowedTrimmed = it.substringAfter("://")
            (allowedSchema.isBlank() || originSchema == allowedSchema) &&
                    (allowedTrimmed == "*" ||
                            allowedTrimmed == originTrimmed ||
                            (allowedTrimmed.startsWith('*') && originTrimmed.endsWith(allowedTrimmed.removePrefix("*"))))
        }
}

/**
 * CorsInterceptor which will apply cors headers to responses as necessary.
 * It will also add OPTIONS handling for CORS Pre-flight requests
 *
 * @param config A runtime CorsSettings object. This is the configuration for the values used in the cors headers.
 */
public class CorsInterceptor(private val config: Runtime<CorsSettings>) : HttpInterceptor, WebSocketHandlerInterceptor {
    override val name: String = "CORS"

    context(runtime: ServerRuntime)
    override suspend fun intercept(
        request: HttpRequest<*>,
        cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse,
    ): HttpResponse {
        val config = config()
        val origin = request.headers[HttpHeader.Origin]?.root ?: return cont(request)

        val originAllowed = config.limitToDomains?.let { originMatches(it, origin) } ?: true

        if (config.forbidOnMatchFail && !originAllowed) throw ForbiddenException()

        val baseResponse = if (request.path.method == HttpMethod.OPTIONS) {

            val perEndpoint = listOf(
                HttpMethod.GET,
                HttpMethod.POST,
                HttpMethod.PUT,
                HttpMethod.PATCH,
                HttpMethod.DELETE,
                HttpMethod.OPTIONS,
                HttpMethod.HEAD,
            ).associateWith { method ->
                runtime.server.endpoints.match(
                    runtime.externalSerialization.stringArrayFormat,
                    request.path.pathSegments
                ) { it.http[method] }
            }

            val existingMethods = perEndpoint.entries.filter { it.value != null }.mapTo(HashSet()) { it.key }

            if (existingMethods.contains(HttpMethod.GET)) existingMethods += HttpMethod.HEAD

            if (existingMethods.isEmpty()) throw NotFoundException()
            else if (!originAllowed) return HttpResponse(status = HttpStatus.NoContent)
            else HttpResponse(
                status = HttpStatus.NoContent,
                headers = HttpHeaders {
                    set(
                        HttpHeader.AccessControlAllowMethods,
                        (config.limitToMethods?.let { limit -> existingMethods.filter { limit.contains(it.toString()) } }
                            ?: existingMethods).joinToString(",")
                    )
                }
            )
        } else {
            val response = cont(request)
            if (!originAllowed) return response
            else response
        }

        return baseResponse.copy(
            headers = baseResponse.headers + HttpHeaders {
                set(HttpHeader.AccessControlAllowOrigin, origin)

                if (config.allowCredentials) set(HttpHeader.AccessControlAllowCredentials, "true")

                if (request.path.method == HttpMethod.OPTIONS) {
                    set(
                        HttpHeader.AccessControlAllowHeaders,
                        config.limitToHeaders?.joinToString(",")
                            ?: request.headers.getMany(HttpHeader.AccessControlRequestHeaders).joinToString(",") { it.root }
                    )
                    config.cacheLength?.let {
                        set(HttpHeader.AccessControlMaxAge, it.toString())
                    }
                } else {
                    config.exposedHeaders
                        .takeUnless { it.isEmpty() }
                        ?.joinToString()
                        ?.let { set(HttpHeader.AccessControlExposeHeaders, it) }
                }
            }
        )
    }

    override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> {
        return object : WebSocketHandler<PATH, T> by handler {
            context(serverRuntime: ServerRuntime)
            override suspend fun willConnect(request: WebSocketConnectRequest<PATH>): T {
                val origin = request.headers[HttpHeader.Origin]?.root ?: return handler.willConnect(request)
                if (config().limitToDomains?.let { originMatches(it, origin) } == false) throw ForbiddenException()
                return handler.willConnect(request)
            }
        }
    }
}
