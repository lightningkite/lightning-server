package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.pathing.PathSpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Interface for handling HTTP requests and producing responses.
 *
 * Implement this interface or use the [HttpHandler] function to create handlers for your endpoints.
 * Handlers run with a [ServerRuntime] context providing access to services and settings.
 *
 * Example:
 * ```kotlin
 * val endpoint = path.path("hello").get bind HttpHandler { request ->
 *     HttpResponse.plainText("Hello, ${request.queryParameters["name"] ?: "World"}!")
 * }
 * ```
 *
 * @param PATH The PathSpec type describing the structure of the endpoint's path
 */
public interface HttpHandler<PATH : PathSpec> {
    /**
     * The maximum duration this handler should run before timing out.
     * Defaults to 30 seconds. Override to customize per-handler timeouts.
     */
    public val timeout: Duration get() = 30.seconds

    /**
     * Handles an HTTP request and returns a response.
     *
     * This function runs in the context of a [ServerRuntime], providing access to
     * server settings, databases, caches, and other services.
     *
     * @param request The incoming HTTP request
     * @return The HTTP response to send back to the client
     */
    context(server: ServerRuntime)
    public suspend fun handle(request: HttpRequest<PATH>): HttpResponse
}

/**
 * Creates an [HttpHandler] from a lambda function.
 *
 * This is the most common way to define HTTP handlers in Lightning Server applications.
 *
 * Example:
 * ```kotlin
 * val getUser = path.path("users").arg<String>("id").get bind HttpHandler { request ->
 *     val userId = request.path.arg1
 *     val user = database().table<User>().get(userId)
 *     HttpResponse.json(user)
 * }
 * ```
 *
 * @param timeout The maximum duration for this handler (default: 30 seconds)
 * @param handler The lambda that handles the request and returns a response
 * @return An HttpHandler implementation wrapping the lambda
 */
public fun <PATH : PathSpec> HttpHandler(
    timeout: Duration = 30.seconds,
    handler: suspend context(ServerRuntime) (HttpRequest<PATH>) -> HttpResponse
): HttpHandler<PATH> = object : HttpHandler<PATH> {
    override val timeout: Duration = timeout

    context(server: ServerRuntime)
    override suspend fun handle(request: HttpRequest<PATH>): HttpResponse {
        return handler(server, request)
    }
}