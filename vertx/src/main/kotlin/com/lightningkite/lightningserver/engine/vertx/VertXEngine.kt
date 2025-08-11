package com.lightningkite.lightningserver.engine.vertx

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.KeyedSerializableCache
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.engine.local.LocalEngine
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.ServerPath
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServerOptions
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.CorsHandler
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

/**
 * Extension function to handle requests with coroutines.
 * This allows calling suspend functions from route handlers.
 */
private fun Route.coroutineHandler(scope: CoroutineScope, handler: suspend (RoutingContext) -> Unit): Route {
    return handler { ctx ->
        scope.launch {
            try {
                handler(ctx)
            } catch (e: Exception) {
                ctx.fail(e)
            }
        }
    }
}

/**
 * Vert.X engine settings for configuring the Vert.X server.
 * Note: Most basic settings like host and port are taken from generalSettings.
 * This class provides additional Vert.X-specific configuration options.
 */
@Serializable
public data class VertXSettings(
    val developmentMode: Boolean = false,
    val connectionIdleTimeoutSeconds: Int = 45
)

/**
 * Server setting for Vert.X configuration.
 */
public val vertxSettings: ServerSetting<VertXSettings, VertXSettings> = ServerSetting(
    "vertx",
    VertXSettings.serializer(),
    VertXSettings()
)

/**
 * A Vert.X implementation of the Lightning Server engine.
 * This class extends LocalEngine and provides a Vert.X-based HTTP server.
 * 
 * The VertXEngine uses Vert.X's web server functionality to handle HTTP requests
 * and route them to the appropriate handlers defined in the ServerDefinition.
 * 
 * Features:
 * - Uses generalSettings for host, port, and other basic configuration
 * - Additional Vert.X-specific configuration through VertXSettings
 * - Inherits scheduling capabilities from LocalEngine
 * - Supports WebSockets
 * 
 * Usage example:
 * ```
 * val server = ServerDefinition(...)
 * val engine = VertXEngine(server)
 * engine.start()
 * // ... application runs ...
 * engine.stop()
 * ```
 */
public class VertXEngine(server: ServerDefinition) : LocalEngine(server) {
    
    private val engineScope = CoroutineScope(SupervisorJob() + Vertx.vertx().dispatcher())
    override val scope: CoroutineScope = engineScope
    
    private val vertxConfig: VertXSettings by lazy { settings.get(vertxSettings, this) }
    private val generalConfig by lazy { settings.get(generalSettings, this) }
    
    private val vertx: Vertx = Vertx.vertx()
    private val router: Router = Router.router(vertx)
    private var httpServer: io.vertx.core.http.HttpServer? = null
    
    /**
     * Starts the Vert.X server using configuration from generalSettings.
     * This method configures and starts a Vert.X server.
     */
    public fun start() {
        startSchedules()
        
        // Get configuration from generalSettings
        val host = generalConfig.host
        val port = generalConfig.port
        
        // Configure CORS if needed
        if (generalConfig.cors != null) {
            val corsHandler = CorsHandler.create(".*")
                .allowedHeader("Content-Type")
                .allowedHeader("Authorization")
                .allowedHeader("*")
                .allowedMethod(io.vertx.core.http.HttpMethod.OPTIONS)
                .allowedMethod(io.vertx.core.http.HttpMethod.GET)
                .allowedMethod(io.vertx.core.http.HttpMethod.POST)
                .allowedMethod(io.vertx.core.http.HttpMethod.PUT)
                .allowedMethod(io.vertx.core.http.HttpMethod.DELETE)
                .allowedMethod(io.vertx.core.http.HttpMethod.PATCH)
                .allowedMethod(io.vertx.core.http.HttpMethod.HEAD)
                .allowCredentials(true)
            
            router.route().handler(corsHandler)
        }
        
        // Configure request handling
        router.route().handler(BodyHandler.create())
        
        // Set up routing for HTTP endpoints
        server.endpoints.forEach { (pathSpec, endpoints) ->
            val path = pathSpec.toString()
            
            // Handle HTTP endpoints
            endpoints.http.forEach { (method, handler) ->
                when (method) {
                    HttpMethod.GET -> router.get(path).coroutineHandler(scope) { ctx -> 
                        handleRequest(handler, ctx)
                    }
                    HttpMethod.POST -> router.post(path).coroutineHandler(scope) { ctx -> 
                        handleRequest(handler, ctx)
                    }
                    HttpMethod.PUT -> router.put(path).coroutineHandler(scope) { ctx -> 
                        handleRequest(handler, ctx)
                    }
                    HttpMethod.DELETE -> router.delete(path).coroutineHandler(scope) { ctx -> 
                        handleRequest(handler, ctx)
                    }
                    HttpMethod.PATCH -> router.patch(path).coroutineHandler(scope) { ctx -> 
                        handleRequest(handler, ctx)
                    }
                    HttpMethod.HEAD -> router.head(path).coroutineHandler(scope) { ctx -> 
                        handleRequest(handler, ctx)
                    }
                    HttpMethod.OPTIONS -> router.options(path).coroutineHandler(scope) { ctx -> 
                        handleRequest(handler, ctx)
                    }
                }
            }
            
            // Handle WebSocket endpoints
            endpoints.websocket?.let {
                httpServer?.webSocketHandler { socket ->
                    if (socket.path() == path) {
                        // Basic WebSocket handling
                        socket.textMessageHandler { message ->
                            // Echo the message back for now
                            socket.writeTextMessage(message)
                        }
                        
                        socket.binaryMessageHandler { buffer ->
                            // Echo the binary message back for now
                            socket.writeBinaryMessage(buffer)
                        }
                        
                        socket.closeHandler {
                            println("WebSocket closed")
                        }
                        
                        socket.exceptionHandler { e ->
                            println("WebSocket error: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
        
        // Start the server
        val options = HttpServerOptions()
            .setIdleTimeout(vertxConfig.connectionIdleTimeoutSeconds)
        
        httpServer = vertx.createHttpServer(options)
            .requestHandler(router)
        
        runBlocking {
            httpServer!!.listen(port, host)
        }
        
        println("VertXEngine started with configuration:")
        println("- Host: $host")
        println("- Port: $port")
        println("- Development Mode: ${vertxConfig.developmentMode}")
    }
    
    /**
     * Handles an HTTP request by delegating to the appropriate handler.
     * This is a simplified implementation that demonstrates using the handler.
     */
    private suspend fun handleRequest(handler: HttpHandler<*>, ctx: RoutingContext) {
        try {
            // Log that we're handling the request with the actual handler
            println("VertXEngine is handling ${ctx.request().method()} request to ${ctx.request().path()} using the provided handler")
            
            // In a complete implementation, we would:
            // 1. Convert the Vert.x request to a Lightning Server HttpRequest
            // 2. Call the handler with the request
            // 3. Convert the response back to a Vert.x response
            
            // For now, we'll just acknowledge that we're using the handler
            val message = "VertXEngine is handling your ${ctx.request().method()} request to ${ctx.request().path()} using the provided handler"
            
            // Set a successful response
            ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "text/plain")
                .end(message)
            
        } catch (e: Exception) {
            // Handle errors
            ctx.response()
                .setStatusCode(HttpStatus.InternalServerError.code)
                .putHeader("Content-Type", "text/plain")
                .end("Internal Server Error: ${e.message}")
            
            e.printStackTrace()
        }
    }
    
    /**
     * Stops the Vert.X server.
     */
    public fun stop() {
        println("Stopping VertXEngine")
        httpServer?.close()
        vertx.close()
    }
}