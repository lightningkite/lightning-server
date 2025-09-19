package com.lightningkite.lightningserver.engine.vertx

import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.engine.local.LocalEngine
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.http.ServerWebSocket
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable


@Serializable
public data class VertxRuntimeSettings(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val realIpHeader: String? = null,
)

internal val vertxRunConfig: ServerSetting.Direct<VertxRuntimeSettings> = ServerSetting(
    "vertxRunConfig",
    VertxRuntimeSettings(),
    VertxRuntimeSettings.serializer()
)


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

    private val vertx: Vertx = Vertx.vertx()

    override val settings: ServerSettings = ServerSettings(super.settings.settings.plus(vertxRunConfig).toSet())
    private val vertXScope = CoroutineScope(SupervisorJob() + Vertx.vertx().dispatcher())

    private var httpServer: io.vertx.core.http.HttpServer? = null


    /**
     * Starts the Vert.X server using configuration from generalSettings.
     * This method configures and starts a Vert.X server.
     */
    public fun start() {
        startSchedules()

        val router: Router = Router.router(vertx)

        // Set up routing for HTTP endpoints
        server.endpoints.forEach { (pathSpec, endpoints) ->
            val path = pathSpec.toString()

            endpoints.http.forEach { (method, handler) ->
                router.route(io.vertx.core.http.HttpMethod.valueOf(method.toString()), path)
                    .coroutineHandler(vertXScope) { ctx ->
                        handleRequest(handler, ctx)
                    }
            }

            // Handle WebSocket endpoints
            endpoints.websocket?.also { socketHandler: WebSocketHandler<*, *> ->
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

        httpServer = vertx.createHttpServer(options)
            .requestHandler(router)
            .let {
                if (server.endpoints.any { it.value.websocket != null }) {
                    it.webSocketHandler { incoming: ServerWebSocket ->

                        @Suppress("UNCHECKED_CAST")
                        val handler: WebSocketHandler<PathSpec, Any?> =
                            (server.endpoints.match(this.externalSerialization.stringArrayFormat, incoming.path())
                                ?.value?.websocket as? WebSocketHandler<PathSpec, Any?>)
                                ?: run {
                                    incoming.reject()
                                    return@webSocketHandler
                                }


                        var queryParams = incoming.query().split('&').map {
                            val split = it.split('=')
                            if (split.size != 2) incoming.reject()
                            split.first() to split.last()
                        }
                        // TODO: Remove this fugly hack and deal with websocket auth better
                        queryParams = queryParams.flatMap {
                            if (it.first == "path") listOf(it) + it.second.substringAfter('?').split('&')
                                .map { it.substringBefore('=') to it.substringAfter('=') }
                            else listOf(it)
                        }

                        incoming.accept()

//                        val request = WebSocketConnectRequest(
//                            path = ServerPath(incoming.path()),
//                            queryParameters = queryParams,
//                            headers = incoming.headers(),
//                            domain = call.request.origin.serverHost,
//                            protocol = call.request.origin.scheme,
//                            sourceIp = settings.get(ktorRunConfig, this@KtorEngine).realIpHeader?.let {
//                                call.request.header(it)
//                                    ?: throw Exception("Real IP address header for proxy '$it' was missing from the request.")
//                            } ?: call.request.origin.remoteAddress,
//                        )
                    }
                } else it
            }

        runBlocking {
            val config = vertxRunConfig()
            httpServer!!.listen(config.port, config.host)
        }

        println("VertXEngine started with configuration:")
    }

    /**
     * Handles an HTTP request by delegating to the appropriate handler.
     * This is a simplified implementation that demonstrates using the handler.
     */
    private suspend fun handleRequest(handler: HttpHandler<*>, ctx: RoutingContext) {
        try {

//            val result: HttpResponse = handler.handle(this, request)
//
//            // Set a successful response
//            ctx.response()
//                .apply {
//                    setStatusCode(result.status.code)
//                    for (header in result.headers.normalizedEntries) {
//                        for (value in header.value) {
//                            putHeader(header.key, value.toHttpString())
//                        }
//                    }
//                    result.body?.also { body ->
//                        putHeader(HttpHeader.ContentType, body.mediaType.toString())
//                        when (val b = body.data) {
//
//                            is Data.Bytes -> {
//                                end(Buffer.buffer(b.data))
//                            }
//
//                            is Data.Text -> end(b.data)
//                            is Data.Sink -> end(Buffer.buffer().in) call . respondOutputStream (type) {
//                                b.emit(this.asSink().buffered())
//                            }
//
//                            is Data.Source -> {
//                                send(ReadStream)
//                            }
//
////                                is HttpContent.Multipart -> TODO()
//                        }
//                    }
//                }

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