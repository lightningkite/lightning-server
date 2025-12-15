package com.lightningkite.lightningserver.engine.ktor

import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.engine.local.LocalEngine
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawWebsocketPath
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.data.Data
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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * Configuration settings for the Ktor HTTP server engine.
 *
 * @property host The host address to bind to (defaults to "0.0.0.0" for all interfaces)
 * @property port The port number to listen on (defaults to 8080)
 * @property realIpHeader Optional header name to extract the real client IP from (useful behind proxies).
 *                        Common values: "X-Forwarded-For", "X-Real-IP"
 */
@Serializable
public data class KtorRuntimeSettings(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val realIpHeader: String? = null,
)

/**
 * Server setting for configuring the Ktor engine runtime parameters.
 */
public val ktorRunConfig: ServerSetting.Direct<KtorRuntimeSettings> = ServerSetting(
    "ktorRunConfig",
    KtorRuntimeSettings(),
    KtorRuntimeSettings.serializer()
)

/**
 * A Lightning Server engine implementation using Ktor as the HTTP server.
 *
 * This is the recommended engine for local development and production deployment
 * when running on a dedicated server or container. It provides:
 * - Full HTTP/1.1 and HTTP/2 support (depending on Ktor engine choice)
 * - WebSocket support
 * - Background task execution
 * - Scheduled task coordination
 *
 * The engine can be started with different Ktor engine factories (Netty, CIO, Jetty, etc.)
 * using the `start()` method.
 *
 * Example usage:
 * ```kotlin
 * val engine = KtorEngine(Server.build())
 * engine.settings.loadFromFile(File("settings.json"), internalSerializersModule)
 * engine.start(Netty) // or CIO, Jetty, etc.
 * ```
 *
 * @param server The server definition to run
 * @param clock The clock to use for timing operations (defaults to System clock, overridable for testing)
 */
public class KtorEngine(server: ServerDefinition, override val clock: Clock = Clock.System) : LocalEngine(server) {

    override val settings: ServerSettings = ServerSettings(super.settings.settings.plus(ktorRunConfig).toSet())

    /**
     * Adapts a Ktor Application to handle Lightning Server requests.
     * Sets up routing for both HTTP and WebSocket connections.
     */
    internal fun Application.adapt() {
        install(WebSockets)

        val runConfig = ktorRunConfig()

        routing {
            route("{...}") {
                handle {
                    val request = call.adapt()
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
                            call.respondSource(source = it, contentType = type, status = code, contentLength = b.size)
                        }

                        is Data.Source -> b.source.use {
                            call.respondSource(source = it, contentType = type, status = code, contentLength = b.size)
                        }
                    }
                }
            }
            webSocket("{...}") {
                val queryParams = call.request.queryParameters.flattenEntries().let(::QueryParameters).pathHack()
                val request = WebSocketConnectRequest(
                    path = RawWebsocketPath(PathSegments.parse(queryParams["path"] ?: call.request.path())),
                    queryParameters = queryParams,
                    headers = call.request.headers.adapt(),
                    domain = call.request.origin.serverHost,
                    protocol = call.request.origin.scheme,
                    sourceIp = runConfig.realIpHeader?.let {
                        call.request.header(it)
                            ?: run { logger.warn { "Real IP address header for proxy '$it' was missing from the request." }; null }
                    } ?: call.request.origin.remoteAddress,
                )

                val match = server.endpoints.match(
                    externalSerialization.stringArrayFormat,
                    request.path.pathSegments
                ) { it.websocket } ?: run {
                    this@webSocket.close(
                        CloseReason(
                            CloseReason.Codes.CANNOT_ACCEPT,
                            "No matching path found for ${request.path}"
                        )
                    )
                    return@webSocket
                }
                val socketHandler = server.compiledWebsocketInterceptors.intercept(match.value)

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
                } catch (e: Throwable) {
                    closingMid?.let { mid ->
                        with(mid) {
                            socketHandler.disconnect(
                                ((e as? HttpStatusException)?.status
                                    ?: HttpStatus.InternalServerError).bestWebsocketCloseCode
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Starts the Ktor server with the specified engine factory.
     *
     * This method:
     * 1. Ensures settings are ready and validated
     * 2. Runs any startup tasks defined in the server
     * 3. Starts the schedule coordinator
     * 4. Starts the HTTP server and blocks until shutdown
     *
     * @param TEngine The type of Ktor engine
     * @param TConfiguration The type of engine configuration
     * @param factory The Ktor engine factory (e.g., Netty, CIO, Jetty)
     *
     * Example:
     * ```kotlin
     * engine.start(Netty)
     * ```
     */
    public fun <TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration> start(factory: ApplicationEngineFactory<TEngine, TConfiguration>) {
        this.settings.ready()
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

/**
 * Implementation of WebSocketConnection for local (in-process) WebSocket handling.
 * Manages subscriptions via PubSub channels and state synchronization.
 */
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
                    WebSocketSubscriptionMessage(topic.topic, topic.pathInContext.rawPathArguments, value),
                )
            }
            yield()
        }
    }

    override suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>) {
        subscriptions[topic.topic]?.cancel()
    }
}

/**
 * Helper class for type-safe retrieval of values with serializers.
 * Wraps a retrieval function that takes a serializer and returns the corresponding value.
 */
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

/*
 * TODO: API Recommendations
 *
 * 1. The start() method uses runBlocking which could block the calling thread unexpectedly.
 *    Consider documenting this behavior or providing a suspending alternative.
 * 2. The watchPaths parameter in embeddedServer is always empty - consider exposing this
 *    for development-time auto-reload functionality.
 * 3. The realIpHeader warning logs but doesn't fail - consider documenting the security
 *    implications of a missing real IP header when behind a proxy.
 * 4. Consider adding a graceful shutdown method that stops schedules and drains connections.
 * 5. The WebSocket path is extracted from query parameter with a "pathHack" - this seems
 *    like a workaround that should be documented or cleaned up.
 */
