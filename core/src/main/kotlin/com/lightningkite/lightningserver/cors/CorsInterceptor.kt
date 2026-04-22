package com.lightningkite.lightningserver.cors

import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
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
            (allowedSchema.isBlank() || originSchema.equals(allowedSchema, ignoreCase = true)) &&
                    (allowedTrimmed.equals("*", ignoreCase = true) ||
                            allowedTrimmed.equals(originTrimmed, ignoreCase = true) ||
                            (allowedTrimmed.startsWith(
                                '*',
                                ignoreCase = true
                            ) && originTrimmed.endsWith(allowedTrimmed.removePrefix("*"), ignoreCase = true)))
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
        val origin = request.headers[HttpHeader.Origin]?.root ?: return cont(request)

        // Check if origin matches allowed patterns
        // null limitToDomains = allow all, non-null = check against patterns
        val originAllowed =
            config.limitToDomains.takeUnless { it == allowAll }?.let { originMatches(it, origin) } ?: true

        // Reject requests with disallowed origins if forbidOnMatchFail is true
        if (config.forbidOnMatchFail && !originAllowed)
            throw ForbiddenException(
                message = "Origin '$origin' is not allowed",
                detail =
                    if (origin.substringAfter("://") == generalSettings().publicUrl.substringAfter("://").substringBefore('/'))
                        "This server's public url is not an allowed domain. Add this server's public url to limitToDomains in the Cors settings to make these requests."
                    else
                        "",
            )

        // Handle preflight OPTIONS requests
        val baseResponse: HttpResponse = if (request.path.method == HttpMethod.OPTIONS) {
            // Discover which HTTP methods are actually implemented for this path
            val existingMethods = runtime.server.endpoints
                .match(
                    runtime.externalSerialization.stringArrayFormat,
                    request.path.pathSegments
                )
                ?.value?.http?.keys?.toMutableSet()
                ?: throw NotFoundException()

            // HEAD is implicitly supported if GET is defined
            if (existingMethods.contains(HttpMethod.GET))
                existingMethods += HttpMethod.HEAD

            // TODO: Potential issue - if origin is not allowed, we still return 204 No Content
            // instead of 403 Forbidden. This reveals that the endpoint exists even for
            // disallowed origins. Consider whether this is desired behavior.
            if (!originAllowed) {
                return HttpResponse(status = HttpStatus.NoContent)
            } else {
                HttpResponse(
                    status = HttpStatus.NoContent,
                    headers = HttpHeaders {
                        add(
                            HttpHeader.AccessControlAllowMethods,
                            // Filter methods by limitToMethods if configured
                            (config.limitToMethods.takeUnless { it == allowAll }
                                ?.let { limit -> existingMethods.filter { limit.contains(it.toString()) } }
                                ?: existingMethods).joinToString(",")
                        )
                    }
                )
            }
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
                add(HttpHeader.AccessControlAllowOrigin, origin)

                // Add Vary: Origin to prevent cache poisoning across different origins
                // This tells caches that the response varies based on the Origin header
                add(HttpHeader.Vary, "Origin")

                if (config.allowCredentials) add(HttpHeader.AccessControlAllowCredentials, "true")

                if (request.path.method == HttpMethod.OPTIONS) {
                    // Preflight-specific headers
                    add(
                        HttpHeader.AccessControlAllowHeaders,
                        // Use configured headers or mirror request headers
                        config.limitToHeaders.takeUnless { it == allowAll }?.joinToString(",")
                            ?: request.headers.getMany(HttpHeader.AccessControlRequestHeaders)
                                .joinToString(",") { it.root }
                    )
                    config.cacheLength?.let {
                        add(HttpHeader.AccessControlMaxAge, it.inWholeSeconds.toString())
                    }
                } else {
                    // Regular request - expose additional headers if configured
                    config.exposedHeaders
                        .takeUnless { it.isEmpty() }
                        ?.joinToString(",")
                        ?.let { add(HttpHeader.AccessControlExposeHeaders, it) }
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
                if (config().limitToDomains.takeUnless { it == allowAll }
                        ?.let { originMatches(it, origin) } == false) throw ForbiddenException()
                return handler.willConnect(request)
            }
        }
    }
}

// TODO: API Recommendations
// 1. The OPTIONS preflight handling reveals endpoint existence even for disallowed origins
//    (returns 204 No Content instead of 403 Forbidden). Consider whether this is desired behavior
//    for security reasons - it could be used to map API endpoints.
// 2. Consider adding metrics/logging for CORS rejections to help debug configuration issues
// 3. The originMatches function could be optimized with compiled regex patterns for wildcard matching
//    instead of string operations on every request
// 4. Consider exposing originMatches as a public testing utility for users to validate their patterns
// 5. The WebSocket origin checking could provide a more descriptive error message rather than
//    just ForbiddenException to help developers debug CORS issues
