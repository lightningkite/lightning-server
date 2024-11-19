package com.lightningkite.lightningserver.ktor

import com.lightningkite.lightningserver.cache.*
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.engine.LocalEngine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.exceptions.exceptionSettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.pubsub.PubSub
import com.lightningkite.lightningserver.schedule.Schedule
import com.lightningkite.lightningserver.schedule.ScheduledTask
import com.lightningkite.lightningserver.schedule.Scheduler
import com.lightningkite.lightningserver.schedule.plus
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.websocket.LocalWebSocketConnection
import com.lightningkite.lightningserver.websocket.WebSocketConnection
import com.lightningkite.lightningserver.websocket.QueryParamWebSocketHandler
import com.lightningkite.lightningserver.websocket.WebSocketClose
import com.lightningkite.lightningserver.websocket.WebSocketConnectRequest
import com.lightningkite.lightningserver.websocket.WebSocketFrame
import com.lightningkite.lightningserver.websocket.WebSocketFrame.*
import com.lightningkite.lightningserver.websocket.WebSocketHandler
import com.lightningkite.lightningserver.websocket.WebSockets
import com.lightningkite.lightningserver.websocket.didConnectTracked
import com.lightningkite.lightningserver.websocket.disconnectTracked
import com.lightningkite.lightningserver.websocket.messageFromClientTracked
import com.lightningkite.lightningserver.websocket.willConnectTracked
import com.lightningkite.now
import io.ktor.http.*
import io.ktor.http.HttpMethod
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.cors.CORSConfig.Companion.CorsSimpleResponseHeaders
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.util.*
import io.ktor.utils.io.jvm.javaio.*
import io.ktor.websocket.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.*
import kotlin.time.Duration.Companion.hours
import com.lightningkite.lightningserver.core.ContentType as HttpContentType

fun Application.lightningServer(pubSub: PubSub, cache: Cache) {
    val myEngine = LocalEngine(pubSub)
    engine = myEngine
    try {
        runBlocking { Tasks.onSettingsReady() }
        install(io.ktor.server.websocket.WebSockets)
        generalSettings().cors?.let {
            install(CORS) {
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Put)
                allowMethod(HttpMethod.Patch)
                allowMethod(HttpMethod.Delete)

                allowHeader(io.ktor.http.HttpHeaders.ContentType)
                allowHeader(io.ktor.http.HttpHeaders.Authorization)

                exposedHeaders.addAll(CorsSimpleResponseHeaders)

                it.allowedDomains.forEach {
                    allowHost(it, listOf("http", "https", "ws", "wss"))
                }
                it.allowedHeaders.forEach {
                    if (it == "*") allowHeaders { true }
                    else allowHeader(it)
                }
            }
        }
        WebSockets.handlers.put(ServerPath.root, QueryParamWebSocketHandler())
        WebSockets.handlers.forEach { (path, rawHandler) ->
            @Suppress("UNCHECKED_CAST")
            rawHandler as WebSocketHandler<Any?>
            routing {
                route(path.toString()) {
                    webSocket {
                        val handler = WebSockets.fullInterceptor(rawHandler)
                        val parts = HashMap<String, String>()
                        var wildcard: String? = null
                        call.parameters.forEach { s, strings ->
                            parts[s] = strings.joinToString("/")
                        }
                        val request = WebSocketConnectRequest(
                            path = path,
                            parts = parts,
                            wildcard = wildcard,
                            queryParameters = call.request.queryParameters.flattenEntries(),
                            headers = call.request.headers.adapt(),
                            domain = call.request.origin.serverHost,
                            protocol = call.request.origin.scheme,
                            sourceIp = generalSettings().realIpHeader?.let {
                                call.request.header(it)
                                    ?: throw Exception("Real IP address header for proxy '$it' was missing from the request.")
                            } ?: call.request.origin.remoteAddress,
                            cache = cache
                        )
                        val startingState = handler.willConnectTracked(path, request)
                        var closingMid: WebSocketConnection<Any?>? = null
                        try {

                            val mid = object : LocalWebSocketConnection<Any?>(
                                startingState = startingState,
                                request = request,
                                handler = handler,
                                path = path,
                                pubSub = pubSub,
                                scope = this@webSocket
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

                            handler.didConnectTracked(path, mid)

                            for (incoming in this.incoming) {
                                val m = when (incoming) {
                                    is Frame.Binary -> Binary(incoming.data)
                                    is Frame.Text -> Text(incoming.readText())
                                    is Frame.Close -> continue
                                    is Frame.Ping -> continue
                                    is Frame.Pong -> continue
                                }
                                handler.messageFromClientTracked(path, mid, m)
                            }
                        } finally {
                            closingMid?.let { mid ->
                                handler.disconnectTracked(path, mid, WebSocketClose.NORMAL)
                            }
                        }
                    }
                }
            }
        }
        Http.endpoints.forEach { entry ->
            routing {
                val routeString = entry.key.path.toString().replace("{...}", "{tailcard...}")
                route(routeString, HttpMethod.parse(entry.key.method.toString())) {
                    handle {
                        val request = call.adapt(entry.key)
                        val result: HttpResponse = Http.execute(request)
                        for (header in result.headers.entries) {
                            call.response.header(header.first, header.second)
                        }
                        call.response.status(HttpStatusCode.fromValue(result.status.code))
                        when (val b = result.body) {
                            null -> {
                                val contentType = call.response.headers[io.ktor.http.HttpHeaders.ContentType]
                                val contentLength = call.response.headers[io.ktor.http.HttpHeaders.ContentLength]
                                if (contentType != null && contentLength != null) {
                                    call.response.call.respondOutputStream(
                                        ContentType.parse(contentType),
                                        HttpStatusCode.NoContent,
                                        contentLength.toLong(),
                                        {})
                                } else
                                    call.respondText("", contentType = null, status = null, configure = { })
                            }

                            is HttpContent.Binary -> call.respondBytes(
                                b.bytes,
                                ContentType.parse(b.type.toString())
                            )

                            is HttpContent.Text -> call.respondText(b.string, ContentType.parse(b.type.toString()))
                            is HttpContent.OutStream -> call.respondOutputStream(ContentType.parse(b.type.toString())) {
                                b.write(
                                    this
                                )
                            }

                            is HttpContent.Stream -> call.respondBytesWriter(ContentType.parse(b.type.toString())) {
                                b.getStream().copyTo(this)
                            }

                            is HttpContent.Multipart -> TODO()
                        }
                    }
                }
            }
        }
        routing {
            route("{param...}") {
                handle {
                    val request = call.adapt(
                        HttpEndpoint(
                            call.request.origin.uri.substringBefore('?').substringBefore('#'),
                            com.lightningkite.lightningserver.http.HttpMethod(call.request.httpMethod.value.uppercase())
                        )
                    )
                    val result = Http.execute(request)
                    for (header in result.headers.entries) {
                        call.response.header(header.first, header.second)
                    }
                    call.response.status(HttpStatusCode.fromValue(result.status.code))
                    when (val b = result.body) {
                        null -> {
                            val contentType = call.response.headers[io.ktor.http.HttpHeaders.ContentType]
                            val contentLength = call.response.headers[io.ktor.http.HttpHeaders.ContentLength]
                            if (contentType != null && contentLength != null) {
                                call.response.headers
                                call.response.call.respondOutputStream(
                                    ContentType.parse(contentType),
                                    HttpStatusCode.NoContent,
                                    contentLength.toLong(),
                                    {})
                            } else
                                call.respondText("", contentType = null, status = null, configure = { })
                        }

                        is HttpContent.Binary -> call.respondBytes(
                            b.bytes,
                            ContentType.parse(b.type.toString())
                        )

                        is HttpContent.Text -> call.respondText(b.string, ContentType.parse(b.type.toString()))
                        is HttpContent.OutStream -> call.respondOutputStream(ContentType.parse(b.type.toString())) {
                            b.write(
                                this
                            )
                        }

                        is HttpContent.Stream -> call.respondBytesWriter(ContentType.parse(b.type.toString())) {
                            b.getStream().copyTo(this)
                        }

                        is HttpContent.Multipart -> TODO()
                    }
                }
            }
        }
        Scheduler.schedules.values.forEach { it: ScheduledTask ->

            fun Schedule.calculateNextRun(now: Instant): Long {
                return when (this) {
                    is Schedule.Frequency -> now.toEpochMilliseconds() + gap.inWholeMilliseconds
                    is Schedule.Daily -> {
                        val local = now.toLocalDateTime(zone)
                        LocalDateTime(
                            if (local.time > time)
                                local.date.plus(DatePeriod(days = 1))
                            else
                                local.date,
                            time
                        )
                            .toInstant(zone)
                            .toEpochMilliseconds()
                    }

                    is Schedule.Cron -> now
                        .toLocalDateTime(zone)
                        .plus(cron)
                        .toInstant(zone)
                        .toEpochMilliseconds()
                }
            }


            @Suppress("OPT_IN_USAGE")
            GlobalScope.launch {
                while (true) {
                    val upcomingRun = cache.get<Long>(it.name + "-nextRun") ?: run {
                        val time = it.schedule.calculateNextRun(now())
                        cache.set<Long>(it.name + "-nextRun", time)
                        time
                    }
                    delay((upcomingRun - System.currentTimeMillis()).coerceAtLeast(1L))
                    val nextRun = it.schedule.calculateNextRun(now())
                    if (cache.setIfNotExists(it.name + "-lock", true)) {
                        cache.set(it.name + "-lock", true, 1.hours)
                        try {
                            Metrics.handlerPerformance(it) {
                                it.handler()
                            }
                        } catch (t: Throwable) {
                            exceptionSettings().report(t)
                        }
                        cache.set<Long>(it.name + "-nextRun", nextRun)
                        cache.remove(it.name + "-lock")
                    } else {
                        delay(1000L)
                    }
                }
            }
        }
        Tasks.tasks  // No registration necessary
        runBlocking { Tasks.onEngineReady() }
    } catch (t: Throwable) {
        t.printStackTrace()
    }
}

/**
 * A helper function to start a Ktor server using GeneralServerSettings and the provided Module.
 */
fun runServer(pubSub: PubSub, cache: Cache) = embeddedServer(
    factory = CIO,
    port = generalSettings().port,
    host = generalSettings().host,
    module = { lightningServer(pubSub, cache) },
    watchPaths = listOf()
).start(wait = true)

fun runServerNetty(pubSub: PubSub, cache: Cache) = embeddedServer(
    factory = Netty,
    port = generalSettings().port,
    host = generalSettings().host,
    module = { lightningServer(pubSub, cache) },
    watchPaths = listOf()
).start(wait = true)

private fun ContentType.adapt(): HttpContentType =
    HttpContentType(type = contentType, subtype = contentSubtype)

private fun HttpContentType.adapt(): ContentType =
    ContentType(contentType = type, contentSubtype = subtype)

internal fun Headers.adapt(): HttpHeaders = HttpHeaders(flattenEntries())

internal suspend fun ApplicationCall.adapt(route: HttpEndpoint): HttpRequest {
    val parts = HashMap<String, String>()
    var wildcard: String? = null
    parameters.forEach { s, strings ->
        if (s == "tailcard") wildcard = strings.joinToString("/")
        parts[s] = strings.joinToString("/")
    }
    return HttpRequest(
        endpoint = route,
        parts = parts,
        wildcard = wildcard,
        queryParameters = request.queryParameters.flattenEntries(),
        headers = request.headers.adapt(),
        body = run {
            val ktorType = request.contentType()
            val myType = ktorType.adapt()
            if (ktorType.contentType == "multipart")
                receiveMultipart().adapt(myType)
            else {
                val stream = receiveStream()
                HttpContent.Stream(
                    { stream },
                    request.contentLength(),
                    request.contentType().adapt()
                )
            }

        },
        domain = request.origin.serverHost,
        protocol = request.origin.scheme,
        sourceIp = generalSettings().realIpHeader?.let {
            request.header(it)
                ?: throw Exception("Real IP address header for proxy '$it' was missing from the request.")
        } ?: request.origin.remoteAddress
    )
}

internal fun MultiPartData.adapt(myType: com.lightningkite.lightningserver.core.ContentType): HttpContent.Multipart {
    return HttpContent.Multipart(myType, object : Flow<HttpContentAndHeaders> {
        override suspend fun collect(collector: FlowCollector<HttpContentAndHeaders>) {
            this@adapt.forEachPart {
                collector.emit(
                    when (it) {
                        is PartData.FormItem -> HttpContent.Multipart.formItem(
                            it.name ?: "",
                            it.value
                        )

                        is PartData.FileItem -> {
                            val h = it.headers.adapt()
                            HttpContent.Multipart.dataItem(
                                key = it.name ?: "",
                                filename = it.originalFileName ?: "",
                                headers = h,
                                content = HttpContent.Stream(
                                    it.streamProvider,
                                    h.contentLength,
                                    it.contentType?.adapt()
                                        ?: com.lightningkite.lightningserver.core.ContentType.Application.OctetStream
                                )
                            )
                        }

                        is PartData.BinaryItem -> {
                            val h = it.headers.adapt()
                            HttpContent.Multipart.dataItem(
                                key = it.name ?: "",
                                filename = "",
                                headers = h,
                                content = HttpContent.Stream(
                                    { it.provider().asStream() },
                                    h.contentLength,
                                    it.contentType?.adapt()
                                        ?: com.lightningkite.lightningserver.core.ContentType.Application.OctetStream
                                )
                            )
                        }

                        is PartData.BinaryChannelItem -> TODO()
                    }
                )
            }
        }
    })
}
