package com.lightningkite.lightningserver.aws

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import com.lightningkite.lightningdb.MultiplexMessage
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.set
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPathMatcher
import com.lightningkite.lightningserver.exceptions.HttpStatusException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pubsub.get
import com.lightningkite.lightningserver.schedule.Scheduler
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.setting
import com.lightningkite.lightningserver.tasks.Task
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.websocket.WebSockets
import io.ktor.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.jsonObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.util.*

abstract class AwsAdapter : RequestStreamHandler {
    companion object {
        val logger: Logger = LoggerFactory.getLogger(AwsAdapter::class.java)
        val httpMatcher by lazy { HttpEndpointMatcher(Http.endpoints.keys.asSequence()) }
        val wsMatcher by lazy { ServerPathMatcher(WebSockets.handlers.keys.asSequence()) }
        val cache by lazy { setting("cache", CacheSettings()) }
    }

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context) {
        runBlocking {
            val asJson = Serialization.json.parseToJsonElement(input.reader().readText()) as JsonObject
            try {
                when {
                    asJson.containsKey("httpMethod") -> Serialization.json.encodeToStream(
                        handleHttp(
                            Serialization.json.decodeFromJsonElement<APIGatewayV2HTTPEvent>(
                                asJson
                            )
                        ), output
                    )
                    asJson["requestContext"]?.jsonObject?.containsKey("connectionId") == true -> Serialization.json.encodeToStream(
                        handleWebsocket(Serialization.json.decodeFromJsonElement<APIGatewayV2WebsocketRequest>(asJson)),
                        output
                    )
                    else -> {
                        println("Input is $asJson")
                        asJson.jankMeADataClass("Something")
                    }
                }
            } catch(e: Exception) {
                println("Input $asJson had trouble")
                e.printStackTrace()
            }
        }
    }

    @Suppress("UNREACHABLE_CODE")
    suspend fun handleWebsocket(event: APIGatewayV2WebsocketRequest): APIGatewayV2HTTPResponse {
        return APIGatewayV2HTTPResponse(200)
        val headers = HttpHeaders(event.multiValueHeaders.entries.flatMap { it.value.map { v -> it.key to v } })
        val body = event.body?.let { raw ->
            if (event.isBase64Encoded)
                HttpContent.Binary(
                    Base64.getDecoder().decode(raw),
                    headers.contentType ?: ContentType.Application.OctetStream
                )
            else
                HttpContent.Text(raw, headers.contentType ?: ContentType.Text.Plain)
        }
        val queryParams =
            (event.multiValueQueryStringParameters ?: mapOf()).entries.flatMap { it.value.map { v -> it.key to v } }
        val path = headers["x-path"] ?: queryParams.find { it.first == "path" }?.second ?: ""
        return if(path.isEmpty()) when (event.requestContext.routeKey) {
            "\$connect" -> APIGatewayV2HTTPResponse(200)
            "\$disconnect" -> APIGatewayV2HTTPResponse(200)
            else -> {
                // This is a ping
                if (body == null || body.length == 0L)
                    return APIGatewayV2HTTPResponse(200)
                val message = Serialization.json.decodeFromString<MultiplexMessage>(event.body)
                val cacheId = event.requestContext.connectionId + "/" + message.channel
                when {
                    message.start -> handleWebsocketConnect(event, cacheId, message.path!!)
                    message.end -> handleWebsocketDisconnect(cacheId)
                    message.data != null -> handleWebsocketMessage(cacheId, message.data!!)
                    else -> APIGatewayV2HTTPResponse(200)
                }
            }
        } else when (event.requestContext.routeKey) {
            "\$connect" -> handleWebsocketConnect(event, event.requestContext.connectionId, path)
            "\$disconnect" -> handleWebsocketDisconnect(event.requestContext.connectionId)
            else -> if (body == null || body.length == 0L)
                return APIGatewayV2HTTPResponse(200)
            else
                handleWebsocketMessage(event.requestContext.connectionId, event.body ?: "")
            }
    }

    suspend fun handleWebsocketConnect(event: APIGatewayV2WebsocketRequest, cacheId: String, path: String): APIGatewayV2HTTPResponse {
        val match = wsMatcher.match(path)
        if (match == null) {
            logger.warn("match is null!"); return APIGatewayV2HTTPResponse(404)
        }
        val handler = WebSockets.handlers[match.path]
        if (handler == null) {
            logger.warn("handler is null!"); return APIGatewayV2HTTPResponse(400)
        }
        cache().set(cacheId, path, Duration.ofHours(1))
        try {
            handler.connect(
                WebSockets.ConnectEvent(
                    path = match.path,
                    parts = match.parts,
                    wildcard = match.wildcard,
                    id = cacheId,
                    queryParameters = event.multiValueQueryStringParameters?.flatMap { it.value.map { v -> it.key to v } } ?: listOf(),
                    headers = HttpHeaders(
                        event.multiValueHeaders.flatMap { it.value.map { v -> it.key to v } },
                    ),
                    domain = event.requestContext.domainName,
                    protocol = "https",
                    sourceIp = event.requestContext.identity.sourceIp
                )
            )
            return APIGatewayV2HTTPResponse(200)
        } catch (e: Exception) {
            return APIGatewayV2HTTPResponse(500, body = Serialization.json.encodeToString(e.message ?: ""))
        }
    }

    suspend fun handleWebsocketMessage(cacheId: String, content: String): APIGatewayV2HTTPResponse {
        val path = cache().get<String>(cacheId) ?: return APIGatewayV2HTTPResponse(400)
        // Reset the cache so it endures longer
        cache().set(cacheId, path, Duration.ofHours(1))
        val match = wsMatcher.match(path)
        if (match == null) {
            logger.warn("match is null!"); return APIGatewayV2HTTPResponse(400)
        }
        val handler = WebSockets.handlers[match.path]
        if (handler == null) {
            logger.warn("handler is null!"); return APIGatewayV2HTTPResponse(400)
        }
        try {
            handler.message(WebSockets.MessageEvent(cacheId, content))
            return APIGatewayV2HTTPResponse(200)
        } catch (e: Exception) {
            return APIGatewayV2HTTPResponse(500, body = Serialization.json.encodeToString(e.message ?: ""))
        }
    }

    suspend fun handleWebsocketDisconnect(cacheId: String): APIGatewayV2HTTPResponse {
        val path = cache().get<String>(cacheId) ?: return APIGatewayV2HTTPResponse(400)
        // Reset the cache so it endures longer
        cache().set(cacheId, path, Duration.ofHours(1))
        val match = wsMatcher.match(path)
        if (match == null) {
            logger.warn("match is null!"); return APIGatewayV2HTTPResponse(400)
        }
        val handler = WebSockets.handlers[match.path]
        if (handler == null) {
            logger.warn("handler is null!"); return APIGatewayV2HTTPResponse(400)
        }
        try {
            handler.disconnect(WebSockets.DisconnectEvent(cacheId))
            return APIGatewayV2HTTPResponse(200)
        } catch (e: Exception) {
            return APIGatewayV2HTTPResponse(500, body = Serialization.json.encodeToString(e.message ?: ""))
        }
    }

    suspend fun handleHttp(event: APIGatewayV2HTTPEvent): APIGatewayV2HTTPResponse {
        val method = HttpMethod(event.httpMethod)
        val path = event.path.removePrefix("/" + event.requestContext.stage)
        val headers = HttpHeaders(event.multiValueHeaders.entries.flatMap { it.value.map { v -> it.key to v } })
        val body = event.body?.let { raw ->
            if (event.isBase64Encoded)
                HttpContent.Binary(
                    Base64.getDecoder().decode(raw),
                    headers.contentType ?: ContentType.Application.OctetStream
                )
            else
                HttpContent.Text(raw, headers.contentType ?: ContentType.Text.Plain)
        }
        val queryParams =
            (event.multiValueQueryStringParameters ?: mapOf()).entries.flatMap { it.value.map { v -> it.key to v } }

        val match = httpMatcher.match(path, method) ?: return APIGatewayV2HTTPResponse(
            statusCode = 404,
            body = "No matching path for '${path}' found"
        )
        val request = HttpRequest(
            endpoint = match.endpoint,
            parts = match.parts,
            wildcard = match.wildcard,
            queryParameters = queryParams,
            headers = headers,
            body = body,
            domain = "input.requestContext.domainName",
            protocol = "https",
            sourceIp = "input.requestContext.http.sourceIp"
        )
        val result = try {
            Http.endpoints[match.endpoint]!!.invoke(request)
        } catch (e: HttpStatusException) {
            e.toResponse(request)
        } catch (e: Throwable) {
            HttpResponse.plainText(e.message ?: "?", HttpStatus.InternalServerError)
        }
        val outHeaders = HashMap<String, String>()
        result.headers.entries.forEach { outHeaders.put(it.first, it.second) }
        val b = result.body
        b?.type?.let { outHeaders.put(HttpHeader.ContentType, it.toString()) }
        b?.length?.let { outHeaders.put(HttpHeader.ContentLength, it.toString()) }
        when {
            b == null -> {
                val response = APIGatewayV2HTTPResponse(
                    statusCode = result.status.code,
                    headers = outHeaders
                )
                return response
            }
            b is HttpContent.Text || b.type.isText -> {
                val response = withContext(Dispatchers.IO) {
                    APIGatewayV2HTTPResponse(
                        statusCode = result.status.code,
                        headers = outHeaders,
                        body = b.text()
                    )
                }
                return response
            }
            else -> {
                val response = withContext(Dispatchers.IO) {
                    APIGatewayV2HTTPResponse(
                        statusCode = result.status.code,
                        headers = outHeaders,
                        body = Base64.getEncoder().encodeToString(b.stream().readAllBytes()),
                        isBase64Encoded = true
                    )
                }
                return response
            }
        }
    }
//
//    override fun handleRequest(input: Map<String, Any?>, context: Context): Any? = runBlocking {
//        println("Input is $input")
//        when {
//            input.containsKey("httpMethod") -> handleHttp(input, context)
//            is ScheduledEvent -> {
//                //TODO: Check if source will work for this
//                Scheduler.schedules.find { it.name == input.source }!!.handler()
//            }
//            is Map<*, *> -> {
//                @Suppress("UNCHECKED_CAST") val input2 = input as Map<String, String>
//                @Suppress("UNCHECKED_CAST") val task = (Tasks.tasks[input.keys.first()]!! as Task<Any?>)
//                task.implementation(this@runBlocking, Serialization.json.decodeFromString(task.serializer, input2.values.first()))
//            }
//            is APIGatewayV2HTTPEvent -> {
//            }
//            is APIGatewayV2WebSocketEvent -> {
//                val c = input.requestContext
//                println("WS event c.resourcePath = ${c.resourcePath}")
//                println("WS event c.routeKey = ${c.routeKey}")
//                println("WS event c.httpMethod = ${c.httpMethod}")
//                println("WS event c.resourceId = ${c.resourceId}")
//                println("WS event c.stage = ${c.stage}")
//                println("WS event input.path = ${input.path}")
//                println("WS event input.pathParameters = ${input.pathParameters}")
//                c.resourcePath
//                fun fail(body: String? = null) = APIGatewayV2WebSocketResponse().apply {
//                    statusCode = 404
//                    this.body = body
//                }
//                fun succeed() = APIGatewayV2WebSocketResponse().apply {
//                    statusCode = 200
//                }
//                succeed()
////                when(c.routeKey) {
////                    "\$connect" -> succeed()
////                    "\$disconnect" -> succeed()
////                    else -> {
////                        // This is a ping
////                        if(input.body.isEmpty())
////                            return@runBlocking succeed()
////                        val message = Serialization.json.decodeFromString<MultiplexMessage>(input.body)
////                        val cacheId = c.connectionId + "/" + message.channel
////                        when {
////                            message.start -> {
////                                val path = message.path!!
////                                val match = wsMatcher.match(path)
////                                if (match == null) { logger.warn("match is null!"); return@runBlocking fail() }
////                                val handler = WebSockets.handlers[match.path]
////                                if (handler == null) { logger.warn("handler is null!"); return@runBlocking fail() }
////                                cache().set(cacheId, path, Duration.ofHours(1))
////                                try {
////                                    handler.connect(
////                                        WebSockets.ConnectEvent(
////                                            path = match.path,
////                                            parts = match.parts,
////                                            wildcard = match.wildcard,
////                                            id = cacheId,
////                                            queryParameters = input.multiValueQueryStringParameters.flatMap { it.value.map { v -> it.key to v } },
////                                            headers = HttpHeaders(
////                                                input.multiValueHeaders.flatMap { it.value.map { v -> it.key to v } },
////                                            ),
////                                            domain = input.requestContext.domainName,
////                                            protocol = "https",
////                                            sourceIp = input.requestContext.identity.sourceIp
////                                        )
////                                    )
////                                    succeed()
////                                } catch(e: Exception) {
////                                    fail(Serialization.json.encodeToString(message.copy(error = e.message)))
////                                }
////                            }
////                            message.end -> {
////                                val path = cache().get<String>(cacheId)
////                                if (path == null) { logger.warn("path at $cacheId is null!"); return@runBlocking fail() }
////                                // Reset the cache so it endures longer
////                                cache().set(cacheId, path, Duration.ofHours(1))
////                                val match = wsMatcher.match(path)
////                                if (match == null) { logger.warn("match is null!"); return@runBlocking fail() }
////                                val handler = WebSockets.handlers[match.path]
////                                if (handler == null) { logger.warn("handler is null!"); return@runBlocking fail() }
////                                try {
////                                    handler.disconnect(WebSockets.DisconnectEvent(cacheId))
////                                    succeed()
////                                } catch(e: Exception) {
////                                    fail(Serialization.json.encodeToString(message.copy(error = e.message)))
////                                }
////                            }
////                            message.data != null -> {
////                                val path = cache().get<String>(cacheId)
////                                if (path == null) { logger.warn("path at $cacheId is null!"); return@runBlocking fail() }
////                                // Reset the cache so it endures longer
////                                cache().set(cacheId, path, Duration.ofHours(1))
////                                val match = wsMatcher.match(path)
////                                if (match == null) { logger.warn("match is null!"); return@runBlocking fail() }
////                                val handler = WebSockets.handlers[match.path]
////                                if (handler == null) { logger.warn("handler is null!"); return@runBlocking fail() }
////                                try {
////                                    handler.message(WebSockets.MessageEvent(cacheId, message.data ?: return@runBlocking fail()))
////                                    succeed()
////                                } catch(e: Exception) {
////                                    fail(Serialization.json.encodeToString(message.copy(error = e.message)))
////                                }
////                            }
////                            else -> succeed() // This is a ping
////                        }
////                    }
////                }
//            }
//            else -> throw UnsupportedOperationException("Not sure how to handle a $input")
//        }
//    }
}