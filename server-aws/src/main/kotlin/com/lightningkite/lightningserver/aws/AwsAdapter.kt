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
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathMatcher
import com.lightningkite.lightningserver.cors.addCors
import com.lightningkite.lightningserver.db.DynamoDbCache
import com.lightningkite.lightningserver.engine.Engine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.exceptions.HttpStatusException
import com.lightningkite.lightningserver.exceptions.report
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.pubsub.get
import com.lightningkite.lightningserver.schedule.Scheduler
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.CorsSettings
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
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import kotlinx.html.A
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.http.SdkHttpFullResponse
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiAsyncClient
import software.amazon.awssdk.services.apigatewaymanagementapi.model.GoneException
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.lambda.LambdaAsyncClient
import software.amazon.awssdk.services.lambda.model.InvocationType
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLEncoder
import java.time.Duration
import java.util.*

abstract class AwsAdapter : RequestStreamHandler {
    @Serializable
    data class TaskInvoke(val taskName: String, val input: String)

    @Serializable
    data class Scheduled(val scheduled: String)

    companion object {
        val logger: Logger = LoggerFactory.getLogger(AwsAdapter::class.java)
        val region by lazy { Region.of(System.getenv("AWS_REGION")) }
        val httpMatcher by lazy { HttpEndpointMatcher(Http.endpoints.keys.asSequence()) }
        val wsMatcher by lazy { ServerPathMatcher(WebSockets.handlers.keys.asSequence()) }
        private val wsCache by lazy {
            DynamoDbCache(
                DynamoDbAsyncClient.builder().region(region).build(),
                generalSettings().wsUrl.substringAfter("://").filter { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }
            )
        }

        fun cache() = wsCache
        val configureEngine by lazy {
            engine = object : Engine {
                val apiGatewayManagement = ApiGatewayManagementApiAsyncClient.builder()
                    .region(region)
                    .endpointOverride(URI.create("https://" + generalSettings().wsUrl.removePrefix("wss://")))
                    .build()

                override suspend fun sendWebSocketMessage(id: String, content: String): Boolean {
                    try {
                        if (id.contains('/')) {
                            //Multiplex
                            val wsId = id.substringBefore('/')
                            val channelId = id.substringAfter('/')
                            val result = apiGatewayManagement.postToConnection {
                                it.connectionId(wsId)
                                it.data(
                                    SdkBytes.fromUtf8String(
                                        Serialization.json.encodeToString(
                                            MultiplexMessage(
                                                channel = channelId,
                                                data = content
                                            )
                                        )
                                    )
                                )
                            }.await()
                            val r = result.sdkHttpResponse()
                            if (!r.isSuccessful) {
                                throw Exception(
                                    "Failed to send socket message to $id: ${r.statusCode()} - ${
                                        try {
                                            r.statusText().get()
                                        } catch (e: Exception) {
                                            "?"
                                        }
                                    } - ${(r as? SdkHttpFullResponse)?.content()?.get()?.use { it.reader().readText() }}"
                                )
                            }
                        } else {
                            val result = apiGatewayManagement.postToConnection {
                                it.connectionId(id)
                                it.data(SdkBytes.fromUtf8String(content))
                            }.await()
                            val r = result.sdkHttpResponse()
                            if (!r.isSuccessful) {
                                throw Exception(
                                    "Failed to send socket message to $id: ${r.statusCode()} - ${
                                        try {
                                            r.statusText().get()
                                        } catch (e: Exception) {
                                            "?"
                                        }
                                    } - ${(r as? SdkHttpFullResponse)?.content()?.get()?.use { it.reader().readText() }}"
                                )
                            }
                        }
                        return true
                    } catch (e: GoneException) {
                        return false
                    }
                }

                val lambdaClient = LambdaAsyncClient.builder()
                    .region(region)
                    .build()

                override suspend fun launchTask(task: Task<Any?>, input: Any?) {
                    lambdaClient.invoke {
                        it.functionName(System.getenv("AWS_LAMBDA_FUNCTION_NAME"))
                        it.invocationType(InvocationType.EVENT)
                        it.payload(
                            SdkBytes.fromUtf8String(
                                Serialization.json.encodeToString(
                                    TaskInvoke.serializer(),
                                    TaskInvoke(task.name, Serialization.json.encodeToString(task.serializer, input))
                                )
                            )
                        )
                    }.await()
                }
            }
            runBlocking { Tasks.onEngineReady() }
            Unit
        }
    }

    init {
        runBlocking { Tasks.onSettingsReady() }
        configureEngine
    }

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context) {
        runBlocking {
            val asJson = Serialization.json.parseToJsonElement(input.reader().readText()) as JsonObject
            try {
                when {
                    asJson.containsKey("taskName") -> Serialization.json.encodeToStream(
                        handleTask(
                            Serialization.json.decodeFromJsonElement(
                                TaskInvoke.serializer(),
                                asJson
                            )
                        ), output
                    )

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

                    asJson.containsKey("scheduled") -> {
                        val parsed: Scheduled = Serialization.json.decodeFromJsonElement(asJson)
                        val schedule =
                            Scheduler.schedules[parsed.scheduled] ?: return@runBlocking APIGatewayV2HTTPResponse(
                                statusCode = 404,
                                body = "No schedule '${parsed.scheduled}' found"
                            )
                        try {
                            Metrics.handlerPerformance(schedule) {
                                schedule.handler()
                            }
                        } catch (e: Exception) {
                            e.report(schedule)
                            APIGatewayV2HTTPResponse(statusCode = 500)
                        }
                    }

                    // The suicide function.  Exists to handle the panic handler to prevent cost issues.
                    asJson.containsKey("panic") -> {
                        try {
                            println("Activating the panic shutdown...")
                            val result = LambdaAsyncClient.builder().region(region).build().putFunctionConcurrency {
                                it.functionName(System.getenv("AWS_LAMBDA_FUNCTION_NAME"))
                                it.reservedConcurrentExecutions(0)
                            }.await()
                            println("Panic got code ${result.sdkHttpResponse().statusCode()}")
                            assert(result.sdkHttpResponse().isSuccessful)
                            APIGatewayV2HTTPResponse(statusCode = 200)
                        } catch (e: Exception) {
                            e.report("Panic")
                            APIGatewayV2HTTPResponse(statusCode = 500)
                        }
                    }

                    else -> {
                        println("Input is $asJson")
                        asJson.jankMeADataClass("Something")
                    }
                }
            } catch (e: Exception) {
                println("Input $asJson had trouble")
                e.printStackTrace()
                e.report(asJson)
            }
        }
    }

    suspend fun handleTask(event: TaskInvoke): APIGatewayV2HTTPResponse {
        return coroutineScope {
            @Suppress("UNCHECKED_CAST") val task = Tasks.tasks[event.taskName] as Task<Any?>?
            if (task == null) {
                logger.error("Task ${event.taskName} not found")
                APIGatewayV2HTTPResponse(statusCode = 404, body = "Task ${event.taskName} not found")
            } else try {
                Metrics.handlerPerformance(task) {
                    task.implementation(this, Serialization.json.decodeFromString(task.serializer, event.input))
                }
                APIGatewayV2HTTPResponse(statusCode = 204)
            } catch (e: Exception) {
                e.report(task)
                APIGatewayV2HTTPResponse(statusCode = 500, body = e.message)
            }
        }
    }

    //    @Suppress("UNREACHABLE_CODE")
    suspend fun handleWebsocket(event: APIGatewayV2WebsocketRequest): APIGatewayV2HTTPResponse {
        val headers =
            HttpHeaders(event.multiValueHeaders?.entries?.flatMap { it.value.map { v -> it.key to v } } ?: listOf())
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
        val multiplexSetId = event.requestContext.connectionId + "-channels"
        return when (event.requestContext.routeKey) {
            "\$connect" -> {
                val path = headers["x-path"] ?: queryParams.find { it.first == "path" }?.second ?: ""
                val isMultiplex = path.isEmpty() || path.trim('/') == "multiplex"
                cache().set("${event.requestContext.connectionId}-isMultiplex", isMultiplex, Duration.ofHours(8))
                if (isMultiplex) APIGatewayV2HTTPResponse(200)
                else handleWebsocketConnect(
                    event,
                    event.requestContext.connectionId,
                    path,
                    event.multiValueQueryStringParameters ?: mapOf()
                )
            }

            "\$disconnect" -> {
                val isMultiplex = isMultiplex()
                cache().remove("${event.requestContext.connectionId}-isMultiplex")
                if (isMultiplex) {
                    cache().get<Set<String>>(multiplexSetId)?.forEach {
                        handleWebsocketDisconnect(event.requestContext.connectionId + "/" + it)
                    }
                    cache().remove(multiplexSetId)
                    APIGatewayV2HTTPResponse(200)
                } else handleWebsocketDisconnect(event.requestContext.connectionId)
            }

            else -> if (body == null || body.length == 0L)
                return APIGatewayV2HTTPResponse(200)
            else if (isMultiplex()) {
                if (event.body.isBlank()) {
                    WebSockets.send(event.requestContext.connectionId, "")
                    return APIGatewayV2HTTPResponse(200)
                }
                val message = Serialization.json.decodeFromString<MultiplexMessage>(event.body)
                val cacheId = event.requestContext.connectionId + "/" + message.channel
                when {
                    message.start -> {
                        try {
                            val result = handleWebsocketConnect(
                                event,
                                cacheId,
                                message.path!!,
                                message.queryParams ?: mapOf()
                            ).also {
                                if (it.statusCode == 200) {
                                    cache().modify<Set<String>>(multiplexSetId, 40, timeToLive = Duration.ofHours(8)) {
                                        it?.plus(message.channel) ?: setOf(message.channel)
                                    }
                                }
                            }
                            WebSockets.send(
                                event.requestContext.connectionId, Serialization.json.encodeToString(
                                    MultiplexMessage(
                                        channel = message.channel,
                                        start = true
                                    )
                                )
                            )
                            result
                        } catch(e: Exception) {
                            WebSockets.send(
                                event.requestContext.connectionId, Serialization.json.encodeToString(
                                    MultiplexMessage(
                                        channel = message.channel,
                                        error = e.message
                                    )
                                )
                            )
                            throw e
                        }
                    }

                    message.end -> {
                        cache().modify<Set<String>>(multiplexSetId, 40, timeToLive = Duration.ofHours(8)) {
                            it?.minus(message.channel) ?: setOf(message.channel)
                        }
                        handleWebsocketDisconnect(cacheId)
                    }

                    message.data != null -> handleWebsocketMessage(cacheId, message.data!!)
                    else -> APIGatewayV2HTTPResponse(200)
                }
            } else
                handleWebsocketMessage(event.requestContext.connectionId, event.body)
        }
    }

    suspend fun handleWebsocketConnect(
        event: APIGatewayV2WebsocketRequest,
        cacheId: String,
        path: String,
        queryParams: Map<String, List<String>>,
    ): APIGatewayV2HTTPResponse {
        logger.debug("Connecting $cacheId")
        val match = wsMatcher.match(path)
        if (match == null) {
            logger.warn("match is null!"); return APIGatewayV2HTTPResponse(404)
        }
        val handler = WebSockets.handlers[match.path]
        if (handler == null) {
            logger.warn("handler is null!"); return APIGatewayV2HTTPResponse(400)
        }
        cache().set(cacheId, path, Duration.ofHours(1))
        val event = WebSockets.ConnectEvent(
            path = match.path,
            parts = match.parts,
            wildcard = match.wildcard,
            id = cacheId,
            queryParameters = queryParams.flatMap { it.value.map { v -> it.key to v } },
            headers = HttpHeaders(
                event.multiValueHeaders?.flatMap { it.value.map { v -> it.key to v } } ?: listOf()
            ),
            domain = event.requestContext.domainName,
            protocol = "https",
            sourceIp = event.requestContext.identity.sourceIp ?: "0.0.0.0"
        )
        try {
            Metrics.handlerPerformance(WebSockets.HandlerSection(match.path, WebSockets.WsHandlerType.CONNECT)) {
                handler.connect(event)
            }
            return APIGatewayV2HTTPResponse(200)
        } catch (e: Exception) {
            e.report(event)
            return APIGatewayV2HTTPResponse(500, body = Serialization.json.encodeToString(e.message ?: ""))
        }
    }

    suspend fun handleWebsocketMessage(cacheId: String, content: String): APIGatewayV2HTTPResponse {
        logger.debug("Handling message $content to $cacheId")
        val path = cache().get<String>(cacheId) ?: run {
            logger.warn("cache has no id $cacheId")
            return APIGatewayV2HTTPResponse(400)
        }
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
        val event = WebSockets.MessageEvent(cacheId, content)
        try {
            Metrics.handlerPerformance(WebSockets.HandlerSection(match.path, WebSockets.WsHandlerType.MESSAGE)) {
                handler.message(event)
            }
            return APIGatewayV2HTTPResponse(200)
        } catch (e: Exception) {
            e.report(event)
            return APIGatewayV2HTTPResponse(500, body = Serialization.json.encodeToString(e.message ?: ""))
        }
    }

    suspend fun handleWebsocketDisconnect(cacheId: String): APIGatewayV2HTTPResponse {
        logger.debug("Disconnecting $cacheId")
        val path = cache().get<String>(cacheId) ?: run {
            logger.warn("cache has no id $cacheId")
            return APIGatewayV2HTTPResponse(400)
        }
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
        val event = WebSockets.DisconnectEvent(cacheId)
        try {
            Metrics.handlerPerformance(WebSockets.HandlerSection(match.path, WebSockets.WsHandlerType.DISCONNECT)) {
                handler.disconnect(event)
            }
            return APIGatewayV2HTTPResponse(200)
        } catch (e: Exception) {
            e.report(event)
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

        val match = httpMatcher.match(path, method) ?: run {
            if (method == HttpMethod.OPTIONS) {
                val origin = headers[HttpHeader.Origin] ?: return APIGatewayV2HTTPResponse(
                    statusCode = 404,
                    body = "No matching path for '${path}' found"
                )
                val cors = generalSettings().cors ?: CorsSettings()
                val matches = cors.allowedDomains.any {
                    it == "*" || it == origin || origin.endsWith(it.removePrefix("*"))
                }
                if (matches) {
                    return APIGatewayV2HTTPResponse(
                        statusCode = HttpStatus.NoContent.code,
                        headers = mapOf(
                            HttpHeader.AccessControlAllowOrigin to (headers[HttpHeader.Origin] ?: "*"),
                            HttpHeader.AccessControlAllowMethods to (headers[HttpHeader.AccessControlRequestMethod]
                                ?: "GET"),
                            HttpHeader.AccessControlAllowHeaders to (cors.allowedHeaders.joinToString(", ")),
                            HttpHeader.AccessControlAllowCredentials to "true",
                        )
                    )
                } else {
                    HttpEndpointMatcher.Match(
                        HttpEndpoint(path, method),
                        parts = mapOf(),
                        wildcard = null
                    )
                }
            } else HttpEndpointMatcher.Match(
                HttpEndpoint(path, method),
                parts = mapOf(),
                wildcard = null
            )
        }
        val request = HttpRequest(
            endpoint = match.endpoint,
            parts = match.parts,
            wildcard = match.wildcard,
            queryParameters = queryParams,
            headers = headers,
            body = body,
            domain = event.requestContext.domainName,
            protocol = "https",
            sourceIp = event.requestContext.identity.sourceIp
        )
        val result = Http.execute(request).addCors(request)
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