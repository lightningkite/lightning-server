package com.lightningkite.lightningserver.engine.ktor

import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.definition.CorsSettings
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.engine.local.LocalEngine
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawPath
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.pubsub.PubSubChannel
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.util.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.io.asSource
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.time.Clock

@Serializable
public data class KtorRuntimeSettings(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val realIpHeader: String? = null,
    val cors: CorsSettings? = null,
)

internal val ktorRunConfig: ServerSetting.Direct<KtorRuntimeSettings> = ServerSetting(
    "ktorRunConfig",
    KtorRuntimeSettings(),
    KtorRuntimeSettings.serializer()
)


public class KtorEngine(server: ServerDefinition, override val clock: Clock = Clock.System) : LocalEngine(server) {

    override val settings: ServerSettings = ServerSettings(super.settings.keys.plus(ktorRunConfig).toSet())

    private fun Application.adapt() {
        install(WebSockets)

        val runConfig = ktorRunConfig()

        runConfig.cors
            ?.also { corsSettings -> install(getLSCorsPlugin(corsSettings)) }

        routing {
            route("{...}") {
                handle {
                    val request = HttpRequest(
                        path = RawPath(call.request.path()),
                        queryParameters = call.request.queryParameters.flattenEntries(),
                        headers = call.request.headers.adapt(),
                        domain = call.request.origin.serverHost,
                        protocol = call.request.origin.scheme,
                        sourceIp = runConfig.realIpHeader?.let {
                            call.request.header(it)
                                ?: throw Exception("Real IP address header for proxy '$it' was missing from the request.")
                        } ?: call.request.origin.remoteAddress,
                        method = HttpMethod(call.request.httpMethod.value),
                        body = run {
                            // Multipart Support?
                            val stream = call.receiveStream()

                            TypedData.sink(
                                call.request.contentType().adapt(),
                                call.request.contentLength() ?: -1
                            ) {
                                it.transferFrom(stream.asSource())
                            }
                        },
                    )
                    val result: HttpResponse = this@KtorEngine.handle(request)

                    for (header in result.headers.normalizedEntries) {
                        for (value in header.value) {
                            call.response.header(header.key, value.toHttpString())
                        }
                    }
                    val code = HttpStatusCode.fromValue(result.status.code)
                    val type = result.body?.mediaType?.toString()?.let { ContentType.parse(it) }
                    when (val b = result.body?.data) {
                        null -> {
                            val contentType = call.response.headers[HttpHeaders.ContentType]
                            val contentLength = call.response.headers[HttpHeaders.ContentLength]
                            if (contentType != null && contentLength != null) {
                                call.response.call.respondOutputStream(
                                    ContentType.parse(contentType),
                                    HttpStatusCode.NoContent,
                                    contentLength.toLong()
                                ) { close() }
                            } else
                                call.respondText(text = "", contentType = type, status = code, configure = { })
                        }

                        is Data.Bytes -> call.respondBytes(bytes = b.data, contentType = type, status = code)

                        is Data.Text -> call.respondText(text = b.data, contentType = type, status = code)
                        is Data.Sink -> b.source().use {
                            call.respondSource(source = it, contentType = type, status = code)
                        }

                        is Data.Source -> b.source.use {
                            call.respondSource(source = it, contentType = type, status = code)
                        }
                    }
                }
            }
            webSocket("{...}") {
                var queryParams = call.request.queryParameters.flattenEntries()
                // TODO: Remove this fugly hack and deal with websocket auth better
                queryParams = queryParams.flatMap {
                    if (it.first == "path") listOf(it) + it.second.substringAfter('?').split('&')
                        .map { part -> part.substringBefore('=') to part.substringAfter('=') }
                    else listOf(it)
                }
                val request = WebSocketConnectRequest(
                    path = RawPath(call.request.path()),
                    queryParameters = queryParams,
                    headers = call.request.headers.adapt(),
                    domain = call.request.origin.serverHost,
                    protocol = call.request.origin.scheme,
                    sourceIp = runConfig.realIpHeader?.let {
                        call.request.header(it)
                            ?: throw Exception("Real IP address header for proxy '$it' was missing from the request.")
                    } ?: call.request.origin.remoteAddress,
                )

                val match = server.endpoints.match(externalSerialization.stringArrayFormat, request.path.string) { it.websocket } ?: run {
                    this@webSocket.close(
                        CloseReason(
                            CloseReason.Codes.CANNOT_ACCEPT,
                            "No matching path found for ${request.path.string}"
                        )
                    )
                    return@webSocket
                }
                val socketHandler = server.websocketInterceptors.fold(match.value) { a, b -> b(a) }

                @Suppress("UNCHECKED_CAST")
                socketHandler as WebSocketHandler<PathSpec, Any?>

                val startingState = socketHandler.willConnect(request)
                var closingMid: WebSocketConnection<PathSpec, Any?>? = null
                try {

                    val mid = object : LocalWebSocketConnection<PathSpec, Any?>(
                        startingState = startingState,
                        request = request,
                        handler = socketHandler,
                        scope = this@webSocket,
                        server = this@KtorEngine,
                        pubSub = { pubSubChannel(it) }
                    ) {
                        override suspend fun send(frame: WebSocketFrame) {
                            this@webSocket.send(
                                when (frame) {
                                    is WebSocketFrame.Binary -> Frame.Binary(true, frame.content)
                                    is WebSocketFrame.Text -> Frame.Text(frame.content)
                                }
                            )
                        }

                        override suspend fun close(reason: WebSocketClose) {
                            this@webSocket.close(CloseReason(reason.code, reason.name))
                        }
                    }
                    closingMid = mid

                    context(mid) { socketHandler.didConnect() }

                    for (incoming in this.incoming) {
                        val m = when (incoming) {
                            is Frame.Binary -> WebSocketFrame(incoming.data)
                            is Frame.Text -> WebSocketFrame(incoming.readText())
                            is Frame.Close -> continue
                            is Frame.Ping -> continue
                            is Frame.Pong -> continue
                        }
                        context(mid) { socketHandler.messageFromClient(m) }
                    }

                    closingMid.let { mid ->
                        context(mid) { socketHandler.disconnect(WebSocketClose.NORMAL) }
                    }
                } catch (e: HttpStatusException) {
                    closingMid?.let { mid ->
                        with(mid) {
                            socketHandler.disconnect(
                                when (e.status.code / 100) {
                                    1, 2, 3 -> WebSocketClose.NORMAL
                                    4 -> WebSocketClose.CLOSED_ABNORMALLY
                                    else -> WebSocketClose.INTERNAL_ERROR
                                }
                            )
                        }
                    }
                } catch (e: Throwable) {
                    closingMid?.let { mid ->
                        context(mid) { socketHandler.disconnect(WebSocketClose.INTERNAL_ERROR) }
                    }
                }
            }
        }
    }

    public fun <TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration> start(factory: ApplicationEngineFactory<TEngine, TConfiguration>) {
        runBlocking { runStartupTasks() }
        startSchedules()
        embeddedServer(
            factory = factory,
            port = ktorRunConfig().port,
            host = ktorRunConfig().host,
            module = { adapt() },
            watchPaths = listOf()
        ).start(wait = true)
    }

}

private abstract class LocalWebSocketConnection<PATH : PathSpec, STORAGE>(
    startingState: STORAGE,
    override val request: WebSocketConnectRequest<PATH>,
    val handler: WebSocketHandler<PATH, STORAGE>,
    val scope: CoroutineScope,
    server: ServerRuntime,
    val pubSub: (request: WebSocketSubscriptionRequest<*, Any?>) -> PubSubChannel<Any?>,
) : WebSocketConnection<PATH, STORAGE>, ServerRuntime by server {
    override var currentState: STORAGE = startingState
    override suspend fun repullState(): STORAGE = currentState
    override suspend fun queueStateUpdate(modification: (STORAGE) -> STORAGE) {
        currentState = modification(currentState)
    }

    override suspend fun updateStateImmediately(modification: (STORAGE) -> STORAGE): STORAGE {
        currentState = modification(currentState)
        return currentState
    }

    val subscriptions = HashMap<WebSocketTopic<*, *>, Job>()

    override suspend fun subscribe(topic: WebSocketSubscriptionRequest<*, *>) {
        @Suppress("UNCHECKED_CAST")
        topic as WebSocketSubscriptionRequest<*, Any?>
        subscriptions[topic.topic]?.cancel()
        subscriptions[topic.topic] = scope.launch {
            pubSub(topic).collect { value ->
                handler.messageFromSubscription(
                    WebSocketSubscriptionMessage(topic.topic, topic.path.rawPathArguments, value),
                )
            }
            yield()
        }
    }

    override suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>) {
        subscriptions[topic.topic]?.cancel()
    }
}

@JvmInline
private value class TypeRetriever(val retriever: (KSerializer<*>) -> Any?) {
    @Suppress("UNCHECKED_CAST")
    operator fun <T> invoke(serializer: KSerializer<T>): T = retriever(serializer) as T

    companion object {
        fun of(retriever: (KSerializer<Nothing>) -> Nothing): TypeRetriever {
            @Suppress("UNCHECKED_CAST")
            return TypeRetriever(retriever as (KSerializer<*>) -> Any?)
        }

        fun literal(value: Any?) = TypeRetriever { value }
    }
}

///**
// * Ktor engine settings for configuring the Ktor server.
// * Note: Most basic settings like host and port are now taken from generalSettings.
// * This class provides additional Ktor-specific configuration options.
// */
//@Serializable
//public data class KtorSettings(
//    val developmentMode: Boolean = false,
//    val connectionIdleTimeoutSeconds: Int = 45,
//    val engineType: EngineType = EngineType.NETTY,
//) {
//    public enum class EngineType {
//        NETTY,
//        CIO
//    }
//}
//
///**
// * Server setting for Ktor-specific configuration.
// */
//public val ktorSettings: ServerSetting<KtorSettings, KtorSettings> = ServerSetting(
//    "ktor",
//    KtorSettings.serializer(),
//    KtorSettings()
//)
//
///**
// * A Ktor implementation of the Lightning Server engine.
// * This class extends LocalEngine and provides a Ktor-based HTTP server.
// *
// * The KtorEngine uses Ktor's embedded server functionality to handle HTTP requests
// * and route them to the appropriate handlers defined in the ServerDefinition.
// *
// * Features:
// * - Uses generalSettings for host, port, and other basic configuration
// * - Additional Ktor-specific configuration through KtorSettings
// * - Support for both Netty and CIO engine types
// * - Inherits scheduling capabilities from LocalEngine
// *
// * Usage example:
// * ```
// * val server = ServerDefinition(...)
// * val engine = KtorEngine(server)
// * engine.start()
// * // ... application runs ...
// * engine.stop()
// * ```
// */
//public class KtorEngine(server: ServerDefinition) : LocalEngine(server) {
//
//    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
//    override val scope: CoroutineScope = engineScope
//
//    private val ktorConfig: KtorSettings by lazy { settings.get(ktorSettings, this) }
//    private val generalConfig by lazy { settings.get(generalSettings, this) }
//
//    private var ktorServer: EmbeddedServer<*, *>? = null
//
//    /**
//     * Starts the Ktor server using configuration from generalSettings.
//     * This method configures and starts a Ktor server with the appropriate engine type.
//     */
//    public fun start() {
//        try {
//            println("Starting KtorEngine...")
//
//            // Start scheduled tasks
//            startSchedules()
//
//            // Get configuration from generalSettings
//            val host = generalConfig.host
//            val port = generalConfig.port
//
//            println("Configuring server with host=$host, port=$port, engine=${ktorConfig.engineType}")
//
//            // Create the appropriate engine based on configuration
//            val server = try {
//                when (ktorConfig.engineType) {
//                    KtorSettings.EngineType.NETTY -> {
//                        println("Using Netty engine")
//                        embeddedServer(Netty, port = port, host = host) {
//                            configureKtor()
//                        }
//                    }
//
//                    KtorSettings.EngineType.CIO -> {
//                        println("Using CIO engine")
//                        embeddedServer(CIO, port = port, host = host) {
//                            configureKtor()
//                        }
//                    }
//                }
//            } catch (e: Exception) {
//                println("Error creating server: ${e.message}")
//                e.printStackTrace()
//                throw e
//            }
//
//            // Start the server
//            try {
//                println("Starting server...")
//                server.start(wait = false)
//                ktorServer = server
//                println("Server started successfully")
//            } catch (e: Exception) {
//                println("Error starting server: ${e.message}")
//                e.printStackTrace()
//                throw e
//            }
//
//            println("KtorEngine started with configuration:")
//            println("- Host: $host")
//            println("- Port: $port")
//            println("- Engine Type: ${ktorConfig.engineType}")
//            println("- Development Mode: ${ktorConfig.developmentMode}")
//        } catch (e: Exception) {
//            println("Failed to start KtorEngine: ${e.message}")
//            e.printStackTrace()
//            throw e
//        }
//    }
//
//    /**
//     * Configures the Ktor application with routes and middleware.
//     */
//    private fun Application.configureKtor() {
//        // Configure CORS if needed
//        if (generalConfig.cors != null) {
//            install(CORS) {
//                // Simple CORS configuration
//                if (generalConfig.debug) {
//                    // In debug mode, allow all origins
//                    anyHost()
//                    allowCredentials = true
//                    allowHeader("*")
//                    allowMethod(io.ktor.http.HttpMethod.Get)
//                    allowMethod(io.ktor.http.HttpMethod.Post)
//                    allowMethod(io.ktor.http.HttpMethod.Put)
//                    allowMethod(io.ktor.http.HttpMethod.Delete)
//                    allowMethod(io.ktor.http.HttpMethod.Patch)
//                    allowMethod(io.ktor.http.HttpMethod.Head)
//                    allowMethod(io.ktor.http.HttpMethod.Options)
//                } else {
//                    // In production, be more restrictive
//                    anyHost() // For simplicity, still allow any host
//                }
//            }
//        }
//
//        // Install WebSockets
//        install(WebSockets)
//
//        // Set up routing
//        routing {
//            // Map all endpoints from the server definition
//            server.endpoints.forEach { (pathSpec, endpoints) ->
//                val path = pathSpec.toString()
//
//                // Handle HTTP endpoints
//                endpoints.http.forEach { (method, handler) ->
//                    when (method) {
//                        HttpMethod.GET -> get(path) { handleRequest(handler, call) }
//                        HttpMethod.POST -> post(path) { handleRequest(handler, call) }
//                        HttpMethod.PUT -> put(path) { handleRequest(handler, call) }
//                        HttpMethod.DELETE -> delete(path) { handleRequest(handler, call) }
//                        HttpMethod.PATCH -> patch(path) { handleRequest(handler, call) }
//                        HttpMethod.HEAD -> head(path) { handleRequest(handler, call) }
//                        HttpMethod.OPTIONS -> options(path) { handleRequest(handler, call) }
//                    }
//                }
//
//                // Handle WebSocket endpoints
//                endpoints.websocket?.let { handler ->
//                    webSocket(path) {
//                        try {
//                            println("WebSocket connection established for $path")
//
//                            // Handle incoming frames
//                            for (frame in incoming) {
//                                try {
//                                    // Log the received frame
//                                    when (frame) {
//                                        is io.ktor.websocket.Frame.Text -> {
//                                            println("Received text frame")
//
//                                            // Echo a response back
//                                            send(io.ktor.websocket.Frame.Text("Echo: Received text frame"))
//                                        }
//
//                                        is io.ktor.websocket.Frame.Binary -> {
//                                            println("Received binary frame of size ${frame.data.size} bytes")
//
//                                            // Echo the binary data back for now
//                                            send(frame)
//                                        }
//
//                                        is io.ktor.websocket.Frame.Close -> {
//                                            println("Received close frame")
//                                        }
//
//                                        else -> {
//                                            println("Received other frame type: ${frame::class.simpleName}")
//                                            // Echo other frame types back
//                                            send(frame)
//                                        }
//                                    }
//                                } catch (e: Exception) {
//                                    println("Error handling WebSocket frame: ${e.message}")
//                                    e.printStackTrace()
//                                }
//                            }
//                        } catch (e: Exception) {
//                            println("WebSocket error: ${e.message}")
//                            e.printStackTrace()
//                        } finally {
//                            println("WebSocket connection closed for $path")
//                        }
//                    }
//                }
//            }
//        }
//    }
//
//    /**
//     * Handles an HTTP request by delegating to the appropriate handler.
//     * This is a simplified implementation that will be expanded in the future.
//     */
//    private suspend fun handleRequest(
//        handler: com.lightningkite.lightningserver.http.HttpHandler<*>,
//        call: ApplicationCall,
//    ) {
//        try {
//            // For now, just respond with a simple message
//            // In a full implementation, this would convert between Ktor and Lightning Server request/response objects
//            call.respondText(
//                "KtorEngine is handling your request to ${call.request.path()} with method ${call.request.httpMethod}",
//                ContentType.Text.Plain
//            )
//
//            // Log that we received a request
//            println("Received ${call.request.httpMethod} request to ${call.request.path()}")
//        } catch (e: Exception) {
//            // Handle errors
//            call.respondText(
//                "Internal Server Error: ${e.message}",
//                ContentType.Text.Plain,
//                HttpStatusCode.InternalServerError
//            )
//            e.printStackTrace()
//        }
//    }
//
//    /**
//     * Stops the Ktor server.
//     * This method gracefully shuts down the Ktor server if it's running.
//     */
//    public fun stop() {
//        try {
//            println("Stopping KtorEngine...")
//
//            ktorServer?.let { server ->
//                try {
//                    println("Gracefully stopping server with timeout...")
//
//                    // Gracefully stop the server with a timeout
//                    server.stop(gracePeriodMillis = 5000, timeoutMillis = 30000)
//                    ktorServer = null
//
//                    println("Server stopped successfully")
//                } catch (e: Exception) {
//                    println("Error stopping server: ${e.message}")
//                    e.printStackTrace()
//
//                    // Try to force stop if graceful shutdown fails
//                    try {
//                        println("Attempting force stop...")
//                        server.stop(gracePeriodMillis = 0, timeoutMillis = 5000)
//                        println("Force stop successful")
//                    } catch (e2: Exception) {
//                        println("Force stop also failed: ${e2.message}")
//                        e2.printStackTrace()
//                    } finally {
//                        ktorServer = null
//                    }
//                }
//            } ?: println("KtorEngine server was not running")
//
//            println("KtorEngine stopped")
//        } catch (e: Exception) {
//            println("Unexpected error during server shutdown: ${e.message}")
//            e.printStackTrace()
//            throw e
//        }
//    }
//}