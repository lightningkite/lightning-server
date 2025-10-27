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

/**
 * Checks if a given origin matches any of the allowed origin patterns.
 *
 * Supports three types of patterns:
 * 1. Exact match: `https://example.com`
 * 2. Scheme wildcard: `*.example.com` (matches any scheme)
 * 3. Subdomain wildcard: `*.example.com`
 *
 * @param allowed List of allowed origin patterns
 * @param origin The origin to check (e.g., "https://sub.example.com")
 * @return true if the origin matches any allowed pattern
 **/
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
 * HTTP interceptor that handles Cross-Origin Resource Sharing (CORS).
 *
 * This interceptor:
 * - Validates incoming origins against configured patterns
 * - Adds appropriate CORS headers to responses
 * - Handles OPTIONS preflight requests automatically
 * - Enforces CORS policy for WebSocket connections
 *
 * ## Preflight Request Handling
 * For OPTIONS requests, the interceptor:
 * 1. Checks which HTTP methods are actually defined for the requested path
 * 2. Filters methods against [CorsSettings.limitToMethods] if configured
 * 3. Returns a 204 No Content response with appropriate CORS headers
 * 4. Returns 404 if no methods are defined for the path
 *
 * ## GOTCHA: Empty limitToDomains
 * An empty list in [CorsSettings.limitToDomains] means NO origins are allowed.
 * Use `null` to allow all origins (mirroring behavior).
 *
 * @param config Runtime configuration for CORS behavior
 **/
public class CorsInterceptor(private val config: Runtime<CorsSettings>) : HttpInterceptor, WebSocketHandlerInterceptor {
    override val name: String = "CORS"
    public companion object {
        private val allowAll = listOf("*")
    }

    context(runtime: ServerRuntime)
    override suspend fun intercept(
        request: HttpRequest<*>,
        cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse,
    ): HttpResponse {
        val config = config()
        // If no Origin header, request is not cross-origin - pass through without CORS headers
        val origin = request.headers[HttpHeader.Origin].takeUnless { it == allowAll }?.root ?: return cont(request)

        // Check if origin matches allowed patterns
        // null limitToDomains = allow all, non-null = check against patterns
        val originAllowed = config.limitToDomains.takeUnless { it == allowAll }?.let { originMatches(it, origin) } ?: true

        // Reject requests with disallowed origins if forbidOnMatchFail is true
        if (config.forbidOnMatchFail && !originAllowed) throw ForbiddenException()

        // Handle preflight OPTIONS requests
        val baseResponse = if (request.path.method == HttpMethod.OPTIONS) {
            // Discover which HTTP methods are actually implemented for this path
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

            // HEAD is implicitly supported if GET is defined
            if (existingMethods.contains(HttpMethod.GET)) existingMethods += HttpMethod.HEAD

            if (existingMethods.isEmpty()) throw NotFoundException()
            // TODO: Potential issue - if origin is not allowed, we still return 204 No Content
            // instead of 403 Forbidden. This reveals that the endpoint exists even for
            // disallowed origins. Consider whether this is desired behavior.
            else if (!originAllowed) return HttpResponse(status = HttpStatus.NoContent)
            else HttpResponse(
                status = HttpStatus.NoContent,
                headers = HttpHeaders {
                    set(
                        HttpHeader.AccessControlAllowMethods,
                        // Filter methods by limitToMethods if configured
                        (config.limitToMethods.takeUnless { it == allowAll }?.let { limit -> existingMethods.filter { limit.contains(it.toString()) } }
                            ?: existingMethods).joinToString(",")
                    )
                }
            )
        } else {
            // Regular (non-preflight) request
            val response = cont(request)
            if (!originAllowed) return response  // No CORS headers for disallowed origins
            else response
        }

        // Add CORS headers to the response
        return baseResponse.copy(
            headers = baseResponse.headers.copy {
                // Always set the actual origin (not wildcard) for allowed requests
                set(HttpHeader.AccessControlAllowOrigin, origin)

                if (config.allowCredentials) set(HttpHeader.AccessControlAllowCredentials, "true")

                if (request.path.method == HttpMethod.OPTIONS) {
                    // Preflight-specific headers
                    set(
                        HttpHeader.AccessControlAllowHeaders,
                        // Use configured headers or mirror request headers
                        config.limitToHeaders.takeUnless { it == allowAll }?.joinToString(",")
                            ?: request.headers.getMany(HttpHeader.AccessControlRequestHeaders)
                                .joinToString(",") { it.root }
                    )
                    config.cacheLength?.let {
                        set(HttpHeader.AccessControlMaxAge, it.toString())
                    }
                } else {
                    // Regular request - expose additional headers if configured
                    config.exposedHeaders
                        .takeUnless { it.isEmpty() }
                        ?.joinToString(",")
                        ?.let { set(HttpHeader.AccessControlExposeHeaders, it) }
                }
            }
        )
    }

    /**
     * Intercepts WebSocket connection requests to enforce CORS policy.
     *
     * WebSocket connections always fail (403 Forbidden) for disallowed origins,
     * regardless of the [CorsSettings.forbidOnMatchFail] setting. This is because
     * WebSocket connections are persistent and must be validated at connection time.
     *
     * @param handler The WebSocket handler to intercept
     * @return A wrapped handler that validates origins before connecting
     */
    override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> {
        return object : WebSocketHandler<PATH, T> by handler {
            context(serverRuntime: ServerRuntime)
            override suspend fun willConnect(request: WebSocketConnectRequest<PATH>): T {
                val origin = request.headers[HttpHeader.Origin]?.root ?: return handler.willConnect(request)
                // WebSocket connections always enforce origin checking (ignore forbidOnMatchFail)
                if (config().limitToDomains.takeUnless { it == allowAll }?.let { originMatches(it, origin) } == false) throw ForbiddenException()
                return handler.willConnect(request)
            }
        }
    }
}

// TODO: API Improvement Recommendations
//
// 1. The originMatches function could benefit from being public (or having a public variant)
//    for testing purposes and for users who want to implement custom CORS logic.
//
// 2. Consider extracting the preflight response logic into a separate function to improve
//    testability and readability. The intercept method is quite long.
//
// 3. The limitToMethods filtering uses string comparison (it.toString()) which may not be
//    case-sensitive. Consider using a case-insensitive comparison or standardizing on uppercase.
//
// 4. When exposedHeaders is joined with joinToString(), it uses the default separator.
//    This should explicitly use joinToString(",") for consistency with other headers.
//
// 5. Consider adding metrics/logging for CORS violations to help diagnose issues in production.
//    Failed origin matches are currently silent unless forbidOnMatchFail is true.
