package com.lightningkite.lightningserver.aws

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import com.lightningkite.lightningdb.MultiplexMessage
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.modify
import com.lightningkite.lightningserver.cache.set
import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPathMatcher
import com.lightningkite.lightningserver.engine.Engine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.exceptions.HttpStatusException
import com.lightningkite.lightningserver.exceptions.reportException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.pubsub.get
import com.lightningkite.lightningserver.schedule.Scheduler
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.settings.setting
import com.lightningkite.lightningserver.tasks.Task
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.websocket.WebSockets
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
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
import java.net.URLEncoder
import java.time.Duration
import java.util.*

abstract class AwsAdapter : RequestStreamHandler {
    companion object {
        val logger: Logger = LoggerFactory.getLogger(AwsAdapter::class.java)
        val httpMatcher by lazy { HttpEndpointMatcher(Http.endpoints.keys.asSequence()) }
        val wsMatcher by lazy { ServerPathMatcher(WebSockets.handlers.keys.asSequence()) }
        val cache by lazy { setting("cache", CacheSettings()) }
        val configureEngine by lazy {
            engine = object: Engine {
                //TODO: Sign V4 request
                val url = "https://" + generalSettings().wsUrl.removePrefix("wss://") + "/%40connections"
                override suspend fun sendWebSocketMessage(id: String, content: String) {
                    if(id.contains('/')) {
                        //Multiplex
                        val wsId = id.substringBefore('/')
                        val channelId = id.substringAfter('/')
                        val result = client.post("$url/${URLEncoder.encode(wsId, Charsets.UTF_8)}") {
                            setBody(TextContent(Serialization.json.encodeToString(MultiplexMessage(
                                channel = channelId,
                                data = content
                            )), io.ktor.http.ContentType.Text.Plain))
                        }
                        if(!result.status.isSuccess()) {
                            logger.warn("Failed to send socket message to $id: ${result.status} - ${try { result.bodyAsText() } catch(e: Exception) { "?" }}")
                        }
                    } else {
                        val result = client.post("$url/${URLEncoder.encode(id, Charsets.UTF_8)}") {
                            setBody(TextContent(content, io.ktor.http.ContentType.Text.Plain))
                        }
                        if(!result.status.isSuccess()) {
                            logger.warn("Failed to send socket message to $id: ${result.status} - ${try { result.bodyAsText() } catch(e: Exception) { "?" }}")
                        }
                    }
                }

                override fun launchTask(task: Task<Any?>, input: Any?) {
                    TODO("Not yet implemented")
                }
            }
            Unit
        }
    }

    init {
        configureEngine
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
            } catch (e: Exception) {
                println("Input $asJson had trouble")
                e.printStackTrace()
            }
        }
    }

    //    @Suppress("UNREACHABLE_CODE")
    suspend fun handleWebsocket(event: APIGatewayV2WebsocketRequest): APIGatewayV2HTTPResponse {
        println("Handling $event")
//        return APIGatewayV2HTTPResponse(200)
        val headers = HttpHeaders(event.multiValueHeaders?.entries?.flatMap { it.value.map { v -> it.key to v } } ?: listOf())
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

        suspend fun isMultiplex() = cache().get<Boolean>("${event.requestContext.connectionId}-isMultiplex") == true
        return when (event.requestContext.routeKey) {
            "\$connect" -> {
                val path = headers["x-path"] ?: queryParams.find { it.first == "path" }?.second ?: ""
                val isMultiplex = path.isEmpty()
                cache().set("${event.requestContext.connectionId}-isMultiplex", isMultiplex)
                if(isMultiplex) APIGatewayV2HTTPResponse(200)
                else handleWebsocketConnect(event, event.requestContext.connectionId, path)
            }
            "\$disconnect" -> {
                cache().remove("${event.requestContext.connectionId}-isMultiplex")
                if(isMultiplex()) {
                    cache().get<Set<String>>(event.requestContext.connectionId)?.forEach {
                        handleWebsocketDisconnect(event.requestContext.connectionId + "/" + it)
                    }
                    APIGatewayV2HTTPResponse(200)
                } else handleWebsocketDisconnect(event.requestContext.connectionId)
            }
            else -> if (body == null || body.length == 0L)
                return APIGatewayV2HTTPResponse(200)
            else if(isMultiplex()) {
                val message = Serialization.json.decodeFromString<MultiplexMessage>(event.body)
                val cacheId = event.requestContext.connectionId + "/" + message.channel
                when {
                    message.start -> handleWebsocketConnect(event, cacheId, message.path!!).also {
                        if(it.statusCode == 200) {
                            cache().modify<Set<String>>(event.requestContext.connectionId, 40) {
                                it?.plus(message.channel) ?: setOf(message.channel)
                            }
                        }
                    }
                    message.end -> {
                        cache().modify<Set<String>>(event.requestContext.connectionId, 40) {
                            it?.minus(message.channel) ?: setOf(message.channel)
                        }
                        handleWebsocketDisconnect(cacheId)
                    }
                    message.data != null -> handleWebsocketMessage(event.requestContext.connectionId, message.data!!)
                    else -> APIGatewayV2HTTPResponse(200)
                }
            } else
                handleWebsocketMessage(event.requestContext.connectionId, event.body)
        }
    }

    suspend fun handleWebsocketConnect(
        event: APIGatewayV2WebsocketRequest,
        cacheId: String,
        path: String
    ): APIGatewayV2HTTPResponse {
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
                    queryParameters = event.multiValueQueryStringParameters?.flatMap { it.value.map { v -> it.key to v } }
                        ?: listOf(),
                    headers = HttpHeaders(
                        event.multiValueHeaders?.flatMap { it.value.map { v -> it.key to v } } ?: listOf()
                    ),
                    domain = event.requestContext.domainName,
                    protocol = "https",
                    sourceIp = event.requestContext.identity.sourceIp
                )
            )
            return APIGatewayV2HTTPResponse(200)
        } catch (e: Exception) {
            reportException(e)
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
            reportException(e)
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
            reportException(e)
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
        } catch (e: Exception) {
            reportException(e)
            try {
                Http.exception(request, e)
            } catch(e: Exception) {
                HttpResponse.plainText(e.message ?: "?", HttpStatus.InternalServerError)
            }
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
}