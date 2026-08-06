package com.lightningkite.lightningserver.engine.jdk

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.engine.local.BodyTooLargeException
import com.lightningkite.lightningserver.engine.local.EngineReliabilitySettings
import com.lightningkite.lightningserver.engine.local.LocalEngine
import com.lightningkite.lightningserver.engine.local.copyLimited
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.services.data.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.io.*
import kotlinx.serialization.Serializable
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.time.Clock

/**
 * Configuration settings for the JDK HTTP server engine.
 *
 * @property host The host address to bind to (defaults to "0.0.0.0" for all interfaces)
 * @property port The port number to listen on (defaults to 8080)
 * @property realIpHeader Optional header name to extract the real client IP from (useful behind proxies)
 * @property reliability Shared engine reliability settings (request timeout, max body size, graceful
 *   shutdown drain, worker-thread pool size). See [EngineReliabilitySettings].
 */
@Serializable
public data class JdkRuntimeSettings(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val realIpHeader: String? = null,
    val reliability: EngineReliabilitySettings = EngineReliabilitySettings(),
)

/**
 * Server setting for configuring the JDK engine runtime parameters.
 */
public val jdkRunConfig: ServerSetting.Direct<JdkRuntimeSettings> = ServerSetting(
    "jdkRunConfig",
    JdkRuntimeSettings(),
    JdkRuntimeSettings.serializer()
)

/**
 * A Lightning Server engine implementation using the JDK's built-in HTTP server.
 *
 * **IMPORTANT: This engine does NOT support WebSockets.**
 *
 * This engine is useful for:
 * - Minimal dependencies (no external server library required)
 * - Simple deployments
 * - Testing environments
 * - Applications that don't need WebSocket support
 *
 * For production use or WebSocket support, consider using KtorEngine instead.
 *
 * @param server The server definition to run
 * @param clock The clock to use for timing operations (defaults to System clock)
 */
public class JdkEngine(
    server: ServerDefinition,
    override val clock: Clock = Clock.System,
) : LocalEngine(server) {

    override val settings: ServerSettings = super.settings + jdkRunConfig

    /**
     * The bounded thread pool that runs request handlers, or null before [start] is called.
     * Retained so it can be shut down during graceful shutdown.
     */
    @Volatile
    private var executor: ThreadPoolExecutor? = null

    /**
     * The running HTTP server, or null before [start] is called. Retained so graceful shutdown
     * can stop it.
     */
    @Volatile
    private var httpServer: HttpServer? = null

    /**
     * Starts the JDK HTTP server.
     *
     * This method:
     * 1. Ensures settings are ready and validated
     * 2. Runs any startup tasks defined in the server
     * 3. Starts the schedule coordinator
     * 4. Creates and starts the HTTP server on a bounded thread pool
     * 5. Registers a SIGTERM/SIGINT shutdown hook for graceful drain
     * 6. Blocks indefinitely (server runs until process termination)
     *
     * **Threading model:** the JDK `HttpServer` is given a bounded [ThreadPoolExecutor] with
     * `reliability.workerThreads ?: availableProcessors() * 2` threads, a bounded backlog queue,
     * and a `CallerRunsPolicy` rejection handler. Each request is still handled synchronously via
     * `runBlocking` on a pool thread (thread-per-request), so handler concurrency is capped by the
     * pool size rather than serialized on the single default executor.
     *
     * Note: This method blocks the calling thread.
     */
    public fun start() {
        // Prepare configuration and lifecycle
        this.settings.ready()
        runBlocking { runStartupTasks() }

        val cfg = jdkRunConfig()
        val reliability = cfg.reliability
        startSchedules(reliability.scheduleLockTtl)
        val maxBody = reliability.maxBodySize.bytes
        val server = HttpServer.create(InetSocketAddress(cfg.host, cfg.port), 0)
        this.httpServer = server

        server.createContext("/") { exchange ->
            try {
                val declaredLength = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
                if (declaredLength != null && declaredLength > maxBody) {
                    exchange.respondPlain(HttpStatus.PayloadTooLarge.code, "Payload Too Large")
                    return@createContext
                }
                val request = exchange.requestToLightningServer(cfg.realIpHeader, this@JdkEngine, maxBody)
                // Request timeout is enforced centrally in ServerRuntime.handle (per-handler HttpHandler.timeout).
                runBlocking {
                    val result: HttpResponse = this@JdkEngine.handle(request)
                    exchange.write(result)
                }
            } catch (e: BodyTooLargeException) {
                // 2.5: streamed body exceeded the cap mid-read.
                try {
                    exchange.respondPlain(HttpStatus.PayloadTooLarge.code, "Payload Too Large")
                } catch (_: Throwable) {
                }
            } catch (e: Throwable) {
                // Ensure we always send some response to avoid client hang
                try {
                    if (exchange.responseBody != null) {
                        exchange.respondPlain(500, "Internal Server Error")
                    }
                } catch (_: Throwable) {
                }
            } finally {
                try {
                    exchange.close()
                } catch (_: Throwable) {
                }
            }
        }

        val threads =
            (reliability.workerThreads ?: (java.lang.Runtime.getRuntime().availableProcessors() * 2)).coerceAtLeast(1)
        val pool = ThreadPoolExecutor(
            threads,
            threads,
            60L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(threads * 8),
            ThreadPoolExecutor.CallerRunsPolicy(),
        )
        this.executor = pool
        server.executor = pool
        server.start()
        registerShutdownHook { shutdown() }
        logger.info { "JdkEngine started on http://${cfg.host}:${cfg.port}" }
    }

    /**
     * Gracefully shuts the engine down: cancels schedules, stops accepting new connections and
     * waits up to [EngineReliabilitySettings.shutdownDrainTimeout] for in-flight requests to finish,
     * disconnects all services, then shuts down the request thread pool. Idempotent.
     */
    public fun shutdown() {
        val server = httpServer ?: return // never started; nothing to drain
        val drain = jdkRunConfig().reliability.shutdownDrainTimeout
        gracefulShutdown(drain) { timeout ->
            // HttpServer.stop blocks up to `delay` seconds for exchanges to complete, then forces close.
            server.stop(timeout.inWholeSeconds.coerceAtLeast(0).toInt())
            executor?.shutdown()
        }
    }
}


/** Sends a plain-text response with the given status code and message. */
private fun HttpExchange.respondPlain(status: Int, message: String) {
    val bytes = message.toByteArray()
    responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

/**
 * Writes a Lightning Server HttpResponse to a JDK HttpExchange.
 * Handles all response types including empty bodies, bytes, text, sinks, and sources.
 */
private suspend fun HttpExchange.write(response: HttpResponse) {
    // Copy headers from response
    for ((key, values) in response.headers.normalizedEntries) {
        for (value in values) {
            this.responseHeaders.add(key, value.toHttpString())
        }
    }

    val status = response.status.code
    response.body?.mediaType?.let {
        responseHeaders.add(HttpHeader.ContentType, it.toString())
    }
    when (val b = response.body?.data) {
        null -> {
            // Support empty body with optional content headers
            val cl = this.responseHeaders.getFirst("Content-Length")?.toLongOrNull()
            val ct = this.responseHeaders.getFirst("Content-Type")
            if (ct != null && cl != null) {
                sendResponseHeaders(status, cl)
                this.responseBody.use { /* no body */ }
            } else {
                sendResponseHeaders(status, -1)
                this.responseBody.use { /* no body */ }
            }
        }

        is Data.Bytes, is Data.Text -> {
            val bytes = b.bytes()
            sendResponseHeaders(status, bytes.size.toLong())
            this.responseBody.use { os -> os.write(bytes) }
        }

        is Data.Sink -> {
            // Unknown length; use chunked
            sendResponseHeaders(status, b.size ?: 0)
            this.responseBody.asSink().buffered().use { sink -> b.emit(sink) }
        }

        is Data.Source -> {
            sendResponseHeaders(status, b.size ?: 0)
            this.responseBody.asSink().buffered().use { sink -> b.source.transferTo(sink) }
        }

        is Data.SuspendingSource, is Data.SuspendingSink -> {
            // A known size is sent as the exact content length; 0 tells the JDK server to use chunked encoding.
            // Data.write streams every variant (blocking ones self-offload to the IO dispatcher).
            sendResponseHeaders(status, b.size ?: 0)
            this.responseBody.asSink().buffered().use { sink -> b.write(sink) }
        }
    }
}

/**
 * Converts a JDK HttpExchange to a Lightning Server HttpRequest.
 *
 * @param realIpHeader Optional header name to extract the real client IP from
 * @param engine The JdkEngine instance (used for logging)
 * @return The converted HttpRequest
 */
private fun HttpExchange.requestToLightningServer(
    realIpHeader: String?,
    engine: JdkEngine,
    maxBody: Long,
): HttpRequest<PathSpec> {
    val method = this.requestMethod
    val uri = this.requestURI
    val queryParams = QueryParameters.parse(uri.rawQuery ?: "")
    val headers = this.requestHeaders.adapt()
    val hostHeader = this.requestHeaders.getFirst("Host") ?: ""
    val domain = hostHeader.substringBefore(":").ifEmpty { this.localAddress.hostString }
    val protocol = if (this.httpContext.server is com.sun.net.httpserver.HttpsServer) "https" else "http"
    val sourceIp = realIpHeader?.let { h ->
        this.requestHeaders.getFirst(h)
            ?: run { engine.logger.warn { "Real IP address header for proxy '$h' was missing from the request." }; null }
    } ?: this.remoteAddress?.address?.hostAddress ?: ""

    val contentTypeHeader = headers.contentType
    val contentLength = headers.contentLength ?: -1L
    val body = if (this.requestBody != null) {
        val src = this.requestBody
        TypedData.sink(
            contentTypeHeader ?: headers.contentType ?: MediaType.Application.OctetStream,
            contentLength
        ) { out ->
            copyLimited(src, maxBody) { b, off, len -> out.write(b, off, len) }
        }
    } else null

    return HttpRequest(
        path = RawHttpEndpoint(uri.path ?: "/", HttpMethod(method)),
        queryParameters = queryParams,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
        body = body
    )
}

/**
 * Converts JDK HTTP Headers to Lightning Server HttpHeaders.
 * Splits comma-separated header values into separate entries.
 */
private fun com.sun.net.httpserver.Headers.adapt(): HttpHeaders = HttpHeaders(
    this.entries.flatMap { (key, values) ->
        values.flatMap { v ->
            v.split(',').map { s -> key to s.trim() }
        }
    }
)

/*
 * TODO: API Recommendations
 *
 * 1. The DEFAULT_BUFFER constant is defined but never used - remove or implement buffering
 * 2. Consider adding graceful shutdown support (currently runs indefinitely)
 * 3. The error handling in start() catches all exceptions and sends generic 500 - consider
 *    more specific error responses based on exception type
 * 4. The adapt() function splits comma-separated headers, but some headers (like Set-Cookie)
 *    shouldn't be split. Consider header-specific handling.
 * 5. Document the WebSocket limitation more prominently (e.g., throw exception if WebSocket
 *    endpoints are registered)
 */