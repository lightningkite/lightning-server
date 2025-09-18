package com.lightningkite.lightningserver.engine.jdk

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.engine.local.LocalEngine
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.Serializable
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.time.Clock

@Serializable
public data class JdkRuntimeSettings(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val realIpHeader: String? = null,
)

public val jdkRunConfig: ServerSetting.Direct<JdkRuntimeSettings> = ServerSetting(
    "jdkRunConfig",
    JdkRuntimeSettings(),
    JdkRuntimeSettings.serializer()
)

/**
 * DOES NOT SUPPORT WEBSOCKETS.
 */
public class JdkEngine(
    server: ServerDefinition,
    override val clock: Clock = Clock.System
) : LocalEngine(server) {

    override val settings: ServerSettings = ServerSettings(super.settings.settings.plus(jdkRunConfig).toSet())

    public fun start() {
        // Prepare configuration and lifecycle
        this.settings.ready()
        runBlocking { runStartupTasks() }
        startSchedules()

        val cfg = jdkRunConfig()
        val httpServer = HttpServer.create(InetSocketAddress(cfg.host, cfg.port), 0)

        httpServer.createContext("/") { exchange ->
            try {
                val request = exchange.requestToLightningServer(cfg.realIpHeader)
                val result: HttpResponse = runBlocking { this@JdkEngine.handle(request) }
                exchange.write(result)
            } catch (e: Throwable) {
                // Ensure we always send some response to avoid client hang
                try {
                    if (exchange.responseBody != null) {
                        val msg = "Internal Server Error"
                        exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
                        exchange.sendResponseHeaders(500, msg.toByteArray().size.toLong())
                        exchange.responseBody.use { out ->
                            out.write(msg.toByteArray())
                        }
                    }
                } catch (_: Throwable) { }
            } finally {
                try { exchange.close() } catch (_: Throwable) {}
            }
        }

        httpServer.executor = null // default executor
        httpServer.start()
        logger.info { "JdkEngine started on http://${cfg.host}:${cfg.port}" }
    }

    private companion object {
        const val DEFAULT_BUFFER = 32 * 1024
    }
}


private fun HttpExchange.write(response: HttpResponse) {
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
            sendResponseHeaders(status, b.size.let {
                if(it < 0) 0 else it
            })
            this.responseBody.use { os ->
                b.emit(os.asSink().buffered())
            }
        }
        is Data.Source -> {
            sendResponseHeaders(status, b.size.let {
                if(it < 0) 0 else it
            })
            this.responseBody.use { os ->
                b.source.transferTo(os.asSink().buffered())
            }
        }
    }
}


private fun HttpExchange.requestToLightningServer(realIpHeader: String?): HttpRequest<PathSpec> {
    val method = this.requestMethod
    val uri = this.requestURI
    val path = PathSegments.parse(uri.path ?: "/")
    val queryParams = QueryParameters.parse(uri.rawQuery ?: "")
    val headers = this.requestHeaders.adapt()
    val hostHeader = this.requestHeaders.getFirst("Host") ?: ""
    val domain = hostHeader.substringBefore(":").ifEmpty { this.localAddress.hostString }
    val protocol = if (this.httpContext.server is com.sun.net.httpserver.HttpsServer) "https" else "http"
    val sourceIp = realIpHeader?.let { h ->
        this.requestHeaders.getFirst(h)!!
    } ?: this.remoteAddress?.address?.hostAddress ?: ""

    val contentTypeHeader = headers.contentType
    val contentLength = headers.contentLength ?: -1L
    val body = if (this.requestBody != null) {
        val src = this.requestBody
        TypedData.sink(contentTypeHeader ?: headers.contentType ?: MediaType.Application.OctetStream, contentLength) { out ->
            out.transferFrom(src.asSource())
        }
    } else null

    return HttpRequest(
        path = RawHttpEndpoint(path, HttpMethod(method)),
        queryParameters = queryParams,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
        body = body
    )
}

private fun com.sun.net.httpserver.Headers.adapt(): HttpHeaders = HttpHeaders(
    this.entries.flatMap { (key, values) ->
        values.flatMap { v ->
            v.split(',').map { s -> key to s.trim() }
        }
    }
)