package com.lightningkite.lightningserver.ktor

import com.lightningkite.lightningserver.HtmlDefaults
import com.lightningkite.lightningserver.cache.CacheInterface
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.set
import com.lightningkite.lightningserver.cache.setIfNotExists
import com.lightningkite.lightningserver.engine.LocalEngine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.exceptions.exceptionSettings
import com.lightningkite.lightningserver.exceptions.reportException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.pubsub.PubSubInterface
import com.lightningkite.lightningserver.pubsub.get
import com.lightningkite.lightningserver.schedule.Schedule
import com.lightningkite.lightningserver.schedule.Scheduler
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.websocket.WebSockets
import io.ktor.http.*
import io.ktor.http.HttpMethod
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
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
import java.time.*
import java.util.*
import kotlin.collections.HashMap
import com.lightningkite.lightningserver.core.ContentType as HttpContentType
import com.lightningkite.lightningserver.http.HttpMethod as MyHttpMethod

fun Application.lightningServer(pubSub: PubSubInterface, cache: CacheInterface) {
    try {
        install(io.ktor.server.websocket.WebSockets)
        install(CORS) {

            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Patch)
            allowMethod(HttpMethod.Delete)

            allowHeader(io.ktor.http.HttpHeaders.ContentType)
            allowHeader(io.ktor.http.HttpHeaders.Authorization)

            exposedHeaders.addAll(CorsSimpleResponseHeaders)

            generalSettings().cors?.let{
                it.allowedDomains.forEach {
                    allowHost(it, listOf("http", "https", "ws", "wss"))
                }
                it.allowedHeaders.forEach {
                    allowHeader(it)
                }
            }

        }
//        install(StatusPages) {
//            exception<Exception> { call, it ->
//                call.respondText(
//                    status = HttpStatusCode.InternalServerError,
//                    contentType = ContentType.Text.Html,
//                    text = HtmlDefaults.basePage(
//                        """
//            <h1>Oh no!</h1>
//            <p>Something went wrong.  We're terribly sorry.  If this continues, see if you can contact the developer.</p>
//        """.trimIndent()
//                    )
//                )
//                call.adapt(HttpEndpoint(call.request.path(), MyHttpMethod(call.request.httpMethod.value)))
//                    .reportException(it)
//            }
//        }
        Http.endpoints.forEach { entry ->
            routing {
                val routeString = entry.key.path.toString().replace("{...}", "{tailcard...}")
                route(routeString, HttpMethod.parse(entry.key.method.toString())) {
                    handle {
                        val request = call.adapt(entry.key)
                        val result = try {
                            entry.value(request)
                        } catch (e: Exception) {
                            Http.exception(request, e)
                        }
                        for (header in result.headers.entries) {
                            call.response.header(header.first, header.second)
                        }
                        call.response.status(HttpStatusCode.fromValue(result.status.code))
                        when (val b = result.body) {
                            null -> call.respondText("")
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
        WebSockets.handlers.forEach { entry ->
            routing {
                route(entry.key.toString()) {
                    webSocket {
                        val parts = HashMap<String, String>()
                        var wildcard: String? = null
                        call.parameters.forEach { s, strings ->
                            if (strings.size > 1) wildcard = strings.joinToString("/")
                            parts[s] = strings.single()
                        }
                        val id = UUID.randomUUID().toString()
                        try {
                            entry.value.connect(
                                WebSockets.ConnectEvent(
                                    path = entry.key,
                                    parts = parts,
                                    wildcard = wildcard,
                                    queryParameters = call.request.queryParameters.flattenEntries(),
                                    id = id,
                                    headers = call.request.headers.adapt(),
                                    domain = call.request.origin.host,
                                    protocol = call.request.origin.scheme,
                                    sourceIp = call.request.origin.remoteHost
                                )
                            )
                            launch {
                                pubSub.get<String>("ws-$id").collect {
                                    send(it)
                                }
                            }
                            for (incoming in this.incoming) {
                                entry.value.message(
                                    WebSockets.MessageEvent(
                                        id = id,
                                        content = (incoming as? Frame.Text)?.readText() ?: ""
                                    )
                                )
                            }
                        } finally {
                            entry.value.disconnect(WebSockets.DisconnectEvent(id))
                        }
                    }
                }
            }
        }
        Scheduler.schedules.forEach {
            @Suppress("OPT_IN_USAGE")
            GlobalScope.launch {
                while (true) {
                    val upcomingRun = cache.get<Long>(it.name + "-nextRun") ?: run {
                        val now = System.currentTimeMillis()
                        cache.set<Long>(it.name + "-nextRun", now)
                        now
                    }
                    delay((upcomingRun - System.currentTimeMillis()).coerceAtLeast(1L))
                    if (cache.setIfNotExists(it.name + "-lock", true)) {
                        cache.set(it.name + "-lock", true, Duration.ofHours(1))
                        try {
                            it.handler()
                        } catch (t: Throwable) {
                            exceptionSettings().report(t)
                        }
                        val nextRun = when (val s = it.schedule) {
                            is Schedule.Daily -> ZonedDateTime.of(LocalDate.now().plusDays(1), s.time, s.zone)
                                .toInstant().toEpochMilli()
                            is Schedule.Frequency -> upcomingRun + s.gap.toMillis()
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
        engine = LocalEngine(pubSub)
        Tasks.startup()
    } catch (t: Throwable) {
        t.printStackTrace()
    }
}

/**
 * A helper function to start a Ktor server using GeneralServerSettings and the provided Module.
 */
fun runServer(pubSub: PubSubInterface, cache: CacheInterface) = embeddedServer(
    factory = CIO,
    port = generalSettings().port,
    host = generalSettings().host,
    module = { lightningServer(pubSub, cache) },
    watchPaths = listOf("classes")
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
        route = route,
        parts = parts,
        wildcard = wildcard,
        queryParameters = request.queryParameters.flattenEntries(),
        headers = request.headers.adapt(),
        body = run {
            val ktorType = request.contentType()
            val myType = ktorType.adapt()
            val stream = receiveStream()
            if (ktorType.contentType == "multipart")
                receiveMultipart().adapt(myType)
            else {
                HttpContent.Stream(
                    { stream },
                    request.contentLength(),
                    request.contentType().adapt()
                )
            }

        },
        domain = request.origin.host,
        protocol = request.origin.scheme,
        sourceIp = request.origin.remoteHost
    )
}
internal fun MultiPartData.adapt(myType: com.lightningkite.lightningserver.core.ContentType): HttpContent.Multipart {
    return HttpContent.Multipart(object : Flow<HttpContent.Multipart.Part> {
        override suspend fun collect(collector: FlowCollector<HttpContent.Multipart.Part>) {
            this@adapt.forEachPart {
                collector.emit(
                    when (it) {
                        is PartData.FormItem -> HttpContent.Multipart.Part.FormItem(
                            it.name ?: "",
                            it.value
                        )
                        is PartData.FileItem -> {
                            val h = it.headers.adapt()
                            HttpContent.Multipart.Part.DataItem(
                                key = it.name ?: "",
                                filename = it.originalFileName ?: "",
                                headers = h,
                                content = HttpContent.Stream(
                                    it.streamProvider,
                                    h.contentLength,
                                    it.contentType?.adapt() ?: HttpContentType.Application.OctetStream
                                )
                            )
                        }
                        is PartData.BinaryItem -> {
                            val h = it.headers.adapt()
                            HttpContent.Multipart.Part.DataItem(
                                key = it.name ?: "",
                                filename = "",
                                headers = h,
                                content = HttpContent.Stream(
                                    { it.provider().asStream() },
                                    h.contentLength,
                                    it.contentType?.adapt() ?: HttpContentType.Application.OctetStream
                                )
                            )
                        }
                        is PartData.BinaryChannelItem -> TODO()
                    }
                )
            }
        }
    }, myType)
}