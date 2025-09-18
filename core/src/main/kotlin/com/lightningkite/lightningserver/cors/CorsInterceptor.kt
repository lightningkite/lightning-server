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
import kotlinx.serialization.Serializable

/**
 * CorsSettings is used to configure Cross Origin Resource Sharing.
 * These settings determine how the server will apply CORS headers during requests.
 *
 * limitToDomains will have a smart match applied to it. This allows for wildcard ("*") subdomains. If you add `https://\*.some.domain` as a limit,
 * any Origin provided that is a sub domain of `some.domain` will be allowed to share. This does mean the wildcard on it's own will
 * match every value in the Origins domain. The wildcard will not be returned in this case, but the Origin domain will be.
 * You can omit the Schema in your limit and any schema will be accepted from the Origin. If you provide a schema than it too must match.
 *
 * The engine will not attempt any smart functionality when it comes to limited values of methods and headers.
 * It does the dumb static method of dumping these values directly into the header responses. Any behavior changes
 * required for allowed credentials is up to the implementor to properly configure.
 *
 * Providing a null value to a limit field will allow literally everything by mirroring the request values into the
 * response headers. This is not recommended outside testing environments.
 *
 * @param limitToDomains Specifies what domains are limited for sharing.
 *      These values are NOT placed directly into the Access-Control-Allow-Origin.
 *      The values will be compared against the incoming Origin header.
 *      If a match is made, then the incoming Origin header will be placed into the response Access-Control-Allow-Origin header.
 *      A `null` value means there are no limits and the request Origin is mirrored onto the response Access-Control-Allow-Origin header.
 * @param limitToHeaders Specifies what headers are limited for sharing.
 *      These values get directly placed into the Access-Control-Allow-Headers header.
 *      A `null` value means there are no limits and the Access-Control-Request-Headers values are mirrored onto the Access-Control-Allow-Headers header.
 * @param limitToMethods Specifies what methods are limited for sharing.
 *      These values get directly placed into the Access-Control-Allow-Methods header.
 *      A `null` value means there are no limits and the Access-Control-Request-Method values are mirrored onto the Access-Control-Allow-Methods header.
 * @param exposedHeaders Specifies what headers are available for sharing beyond the request headers.
 *      These values get directly placed into the Access-Control-Expose-Methods header.
 * @param allowCredentials Specifies if Credentials are allowed for sharing.
 *      If allowCredentials is true, the header Access-Control-Allow-Credentials will be included with the value `true`.
 * @param cacheLength Specifics the allowed length(in seconds) for caching a prefight request.
 *      A non `null` value is placed directly into the Access-Control-Max-Age header.
 *      A `null` value means the header Access-Control-Max-Age is never sent.
 * @param forbidOnMatchFail If `true` ANY request with an `Origin` header that does not match any of the values in
 *      `limitToDomains` will result in an immediate Forbidden response. This response will happen before any further
 *      work is done. If `false` then all request play out as normal, and the headers returned in the response as
 *      expected. Websockets will always result in forbidden in these situations regardless of this value.
 */
@Serializable
public data class CorsSettings(
    val limitToDomains: List<String>? = emptyList(),
    val limitToHeaders: List<String>? = emptyList(),
    val limitToMethods: List<String>? = emptyList(),
    val exposedHeaders: List<String> = emptyList(),
    val allowCredentials: Boolean = false,
    val cacheLength: UInt? = null,
    val forbidOnMatchFail: Boolean = true,
)

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
    override suspend fun handle(
        request: HttpRequest<*>,
        cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse,
    ): HttpResponse {
        val conf = config()
        val origin = request.headers[HttpHeader.Origin]?.root ?: return cont(request)

        val originAllowed = conf.limitToDomains?.let { originMatches(it, origin) } ?: true

        if (conf.forbidOnMatchFail && !originAllowed) throw ForbiddenException()

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
                serverRuntime.server.endpoints.match(
                    serverRuntime.externalSerialization.stringArrayFormat,
                    request.path.pathSegments
                ) { it.http[method] }
            }

            val existingMethods = perEndpoint.entries.filter { it.value != null }.mapTo(HashSet()) { it.key }

            if (existingMethods.contains(HttpMethod.GET)) existingMethods += HttpMethod.HEAD

            if (existingMethods.isEmpty())
                throw NotFoundException()
            else if (!originAllowed) return HttpResponse(status = HttpStatus.NoContent)
            else
                HttpResponse(
                    status = HttpStatus.NoContent,
                    headers = HttpHeaders {
                        set(
                            HttpHeader.AccessControlAllowMethods,
                            (conf.limitToMethods?.let { limit -> existingMethods.filter { limit.contains(it.toString()) } }
                                ?: existingMethods).joinToString(","))
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

                if (conf.allowCredentials == true)
                    set(HttpHeader.AccessControlAllowCredentials, "true")

                if (request.path.method == HttpMethod.OPTIONS) {
                    set(
                        key = HttpHeader.AccessControlAllowHeaders,
                        value = conf.limitToHeaders?.joinToString()
                            ?: request.headers[HttpHeader.AccessControlRequestHeaders]?.root
                            ?: "",
                    )
                    conf.cacheLength
                        ?.also { set(HttpHeader.AccessControlMaxAge, it.toString()) }
                } else {
                    conf.exposedHeaders.takeUnless { it.isEmpty() }?.joinToString()
                        ?.also { set(HttpHeader.AccessControlExposeHeaders, it) }
                }
            }
        )
    }

    override fun <PATH : PathSpec, T> invoke(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> {
        return object : WebSocketHandler<PATH, T> by handler {
            context(serverRuntime: ServerRuntime)
            override suspend fun willConnect(request: WebSocketConnectRequest<PATH>): T {
                val origin = request.headers[HttpHeader.Origin]?.root ?: return handler.willConnect(request)
                if (!(config().limitToDomains?.let { originMatches(it, origin) } ?: true))
                    throw ForbiddenException()
                return handler.willConnect(request)
            }
        }
    }
}
