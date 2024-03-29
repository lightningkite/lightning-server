package com.lightningkite.lightningserver.aws

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import com.lightningkite.lightningserver.SetOnce
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.setIfNotExists
import com.lightningkite.lightningserver.compression.extensionForEngineCompression
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.Disconnectable
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.cors.extensionForEngineAddCors
import com.lightningkite.lightningserver.db.DynamoDbCache
import com.lightningkite.lightningserver.encryption.OpenSsl
import com.lightningkite.lightningserver.engine.Engine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.exceptions.report
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.schedule.Scheduler
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.CorsSettings
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.tasks.Task
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.websocket.QueryParamWebSocketHandler
import com.lightningkite.lightningserver.websocket.WebSocketIdentifier
import com.lightningkite.lightningserver.websocket.WebSockets
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.crac.Core
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.http.SdkHttpFullResponse
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiAsyncClient
import software.amazon.awssdk.services.apigatewaymanagementapi.model.GoneException
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.lambda.LambdaAsyncClient
import software.amazon.awssdk.services.lambda.model.InvocationType
import software.amazon.awssdk.services.s3.S3Client
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.time.Duration
import java.util.*
import org.crac.Resource
import kotlin.system.exitProcess


abstract class AwsAdapter : RequestStreamHandler, Resource {
    init {
        logger.debug("Initializing AwsAdapter...")
    }

    @Serializable
    data class TaskInvoke(val taskName: String, val input: String)

    @Serializable
    data class Scheduled(val scheduled: String)

    companion object {
        val logger: Logger = LoggerFactory.getLogger(AwsAdapter::class.java)
        var preventLambdaTimeoutReuse: Boolean = false

        fun loadSettings(jclass: Class<*>) {
            logger.debug("Loading settings...")
            val root = File(System.getenv("LAMBDA_TASK_ROOT"))
            root.resolve("settings.json").takeIf { it.exists() }?.let {
                it.readBytes().let { loadSettings(it) }
            } ?: root.resolve("settings.enc").takeIf { it.exists() }?.let {
                it.readBytes().let { loadSettings(it) }
            } ?: run {
                S3Client.create().getObject {
                    it.bucket(System.getenv("LIGHTNING_SERVER_SETTINGS_BUCKET")!!)
                    it.key(System.getenv("LIGHTNING_SERVER_SETTINGS_FILE")!!)
                }.use {
                    it.readBytes().let { loadSettings(it) }
                }
            }
        }

        fun loadSettings(bytes: ByteArray) {
            val decryptedBytes = System.getenv("LIGHTNING_SERVER_SETTINGS_DECRYPTION")
                ?.takeIf { it.isNotBlank() }
                ?.let { sha256Password ->
                    OpenSsl.decryptAesCbcPkcs5Sha256(bytes, sha256Password.toByteArray())
                }
                ?: bytes
            Serialization.Internal.json.decodeFromString<Settings>(decryptedBytes.toString(Charsets.UTF_8))
        }

        val region by lazy { Region.of(System.getenv("AWS_REGION")) }
        var cache: () -> Cache by SetOnce {
            val lazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                DynamoDbCache(
                    { DynamoDbAsyncClient.builder().region(region).build() },
                    generalSettings().wsUrl.substringAfter("://")
                        .substringBefore('?')
                        .filter { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }
                )
            }
            return@SetOnce { lazy.value }
        }

        private val backgroundReportingActions = ArrayList<suspend () -> Unit>()
        private val configureEngine by lazy {
            engine = object : Engine {
                val lambdaClient = LambdaAsyncClient.builder()
                    .region(region)
                    .build()

                override suspend fun launchTask(task: Task<Any?>, input: Any?) {
                    lambdaClient.invoke {
                        it.functionName(System.getenv("AWS_LAMBDA_FUNCTION_NAME"))
                        it.qualifier(System.getenv("AWS_LAMBDA_FUNCTION_VERSION"))
                        it.invocationType(InvocationType.EVENT)
                        it.payload(
                            SdkBytes.fromUtf8String(
                                Serialization.json.encodeToString(
                                    TaskInvoke.serializer(),
                                    TaskInvoke(task.name, Serialization.json.encodeToString(task.serializer, input))
                                )
                            )
                        )
                    }.await().let {
                        it.logResult()
                    }
                }

                override fun backgroundReportingAction(action: suspend () -> Unit) {
                    backgroundReportingActions.add(action)
                }
            }
            logger.debug("Running Tasks.onEngineReady()...")
            runBlocking { Tasks.onEngineReady() }
            logger.debug("Tasks.onEngineReady() complete.")
            Unit
        }
    }

    init {
        logger.debug("Running Tasks.onSettingsReady()...")
        runBlocking { Tasks.onSettingsReady() }
        logger.debug("Tasks.onSettingsReady() complete.")
        configureEngine
        Http.matcher
        WebSockets.matcher
        Core.getGlobalContext().register(this)
    }

    override fun beforeCheckpoint(context: org.crac.Context<out Resource>?) {
        logger.debug("beforeCheckpoint() - Preparing all connections...")
        Settings.requirements.forEach { (key, value) ->
            (value() as? Disconnectable)?.let {
                runBlocking {
                    logger.debug("Making InitialConnection to: $key")
                    it.connect()
                    logger.debug("Now Disconnecting $key...")
                    it.disconnect()
                }
            }
        }
        logger.debug("Disconnections complete.")
    }

    override fun afterRestore(context: org.crac.Context<out Resource>?) {
        logger.debug("afterRestore() - opening all connections")
        Settings.requirements.forEach { (key, value) ->
            (value() as? Disconnectable)?.let {
                logger.debug("Connecting $key...")
                runBlocking { it.connect() }
            }
        }
        logger.debug("Connections Complete")
    }

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context) {
        var handleKey: String = "unknown"
        try {
            val asJson = Serialization.json.parseToJsonElement(input.reader().readText()) as JsonObject
            val response: APIGatewayV2HTTPResponse = blockingTimeout(context.remainingTimeInMillis - 5_000L) {
                when {
                    asJson.containsKey("taskName") -> {
                        handleTask(
                            Serialization.json.decodeFromJsonElement(
                                TaskInvoke.serializer(),
                                asJson
                            ).also { handleKey = it.taskName }
                        )
                    }

                    asJson.containsKey("httpMethod") -> handleHttp(
                        Serialization.json.decodeFromJsonElement<APIGatewayV2HTTPEvent>(
                            asJson
                        ).also { handleKey = it.httpMethod + " " + it.path }
                    )

                    asJson["requestContext"]?.jsonObject?.containsKey("connectionId") == true -> handleWebsocket(
                        Serialization.json.decodeFromJsonElement<APIGatewayV2WebsocketRequest>(asJson).also {
                            handleKey = "WS " + it.requestContext?.routeKey
                        }
                    )

                    asJson.containsKey("scheduled") -> {
                        val parsed: Scheduled = Serialization.json.decodeFromJsonElement<Scheduled>(asJson).also {
                            handleKey = "Schedule " + it.scheduled
                        }
                        val schedule =
                            Scheduler.schedules[parsed.scheduled]
                                ?: return@blockingTimeout APIGatewayV2HTTPResponse(
                                    statusCode = 404,
                                    body = "No schedule '${parsed.scheduled}' found"
                                )
                        try {
                            Metrics.handlerPerformance(schedule) {
                                schedule.handler()
                            }
                            APIGatewayV2HTTPResponse(statusCode = 200)
                        } catch (e: Exception) {
                            e.report(schedule)
                            APIGatewayV2HTTPResponse(statusCode = 500)
                        }
                    }

                    // The suicide function.  Exists to handle the panic handler to prevent cost issues.
                    asJson.containsKey("panic") -> {
                        try {
                            logger.info("Activating the panic shutdown...")
                            val result =
                                LambdaAsyncClient.builder().region(region).build().putFunctionConcurrency {
                                    it.functionName(System.getenv("AWS_LAMBDA_FUNCTION_NAME"))
                                    it.reservedConcurrentExecutions(0)
                                }.await()
                            logger.info("Panic got code ${result.sdkHttpResponse().statusCode()}")
                            assert(result.sdkHttpResponse().isSuccessful)
                            APIGatewayV2HTTPResponse(statusCode = 200)
                        } catch (e: Exception) {
                            e.report("Panic")
                            APIGatewayV2HTTPResponse(statusCode = 500)
                        }
                    }

                    else -> {
                        APIGatewayV2HTTPResponse(
                            statusCode = 500,
                            body = "No response available for the handler"
                        )
                    }
                }
            }
            val backgroundRegularHealthActionsJob = GlobalScope.launch {
                println("Running ${backgroundReportingActions.size} backgroundRegularHealthActions...")
                backgroundReportingActions.forEach {
                    try {
                        it()
                    } catch (e: Exception) {
                        e.report()
                    }
                }
            }
            Serialization.json.encodeToStream(response, output)
            output.flush()
            output.close()
            runBlocking {
                backgroundRegularHealthActionsJob.join()
            }
        } catch (e: Exception) {
            // Something basic in processing died, we must report it.
            val ex = Exception("Full lambda failure", e)
            ex.printStackTrace()
            runBlocking {
                ex.report(handleKey)
            }
            if (preventLambdaTimeoutReuse) {
                println("Killing self to prevent potentially broken reuse.  To disable this, set AwsAdapter.preventLambdaTimeoutReuse to false.")
                exitProcess(1)
            }
        } catch (e: StackOverflowError) {
            // StackOverflowError is bad, but not critical.  This lambda could still server other requests.
            val ex = Exception("Full lambda failure", e)
            ex.printStackTrace()
            runBlocking {
                ex.report()
            }
            if (preventLambdaTimeoutReuse) {
                println("Killing self to prevent potentially broken reuse.  To disable this, set AwsAdapter.preventLambdaTimeoutReuse to false.")
                exitProcess(1)
            }
        } catch (e: VirtualMachineError) {
            // If we have a critical error, we need to make sure the process dies so Lambda doesn't attempt to reuse the VM.
            try {
                e.printStackTrace()
                runBlocking {
                    e.report()
                }
            } catch (t: Throwable) { /*squish*/
            }
            println("Killing self to prevent potentially broken reuse due to full VirtualMachineError ${e.message}.")
            exitProcess(1)
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

    val rootWs by lazy { QueryParamWebSocketHandler { cache() } }
    val apiGatewayManagement by lazy {
        ApiGatewayManagementApiAsyncClient.builder()
            .region(region)
            .endpointOverride(URI.create("https://" + generalSettings().wsUrl.removePrefix("wss://")))
            .build()
    }

    val wsType = "aws"

    init {
        WebSocketIdentifier.register(
            type = wsType,
            send = { id, value ->
                try {
                    val result = apiGatewayManagement.postToConnection {
                        it.connectionId(id)
                        it.data(SdkBytes.fromUtf8String(value))
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
                    true
                } catch (e: GoneException) {
                    handleWsDisconnect(id)
                    false
                }
            },
            close = { id ->
                try {
                    val result = apiGatewayManagement.deleteConnection {
                        it.connectionId(id)
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
                    true
                } catch (e: GoneException) {
                    false
                }
            }
        )
    }

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
            (event.multiValueQueryStringParameters
                ?: mapOf()).entries.flatMap { it.value.map { v -> it.key to v.decodeURLPart() } }

        return when (event.requestContext.routeKey) {
            "\$connect" -> {
                val lkEvent = WebSockets.ConnectEvent(
                    path = ServerPath.root,
                    parts = mapOf(),
                    wildcard = null,
                    id = WebSocketIdentifier(wsType, event.requestContext.connectionId),
                    queryParameters = queryParams,
                    headers = headers,
                    domain = event.requestContext.domainName,
                    protocol = "https",
                    sourceIp = event.requestContext.identity.sourceIp ?: "0.0.0.0"
                )
                try {
                    rootWs.connect(lkEvent)
                    APIGatewayV2HTTPResponse(200)
                } catch (e: Exception) {
                    APIGatewayV2HTTPResponse(500, body = Serialization.json.encodeToString(e.message ?: ""))
                }
            }

            "\$disconnect" -> {
                handleWsDisconnect(event.requestContext.connectionId)
            }

            else -> if (body == null || body.length == 0L)
                APIGatewayV2HTTPResponse(200)
            else {
                val lkEvent =
                    WebSockets.MessageEvent(WebSocketIdentifier(wsType, event.requestContext.connectionId), event.body)
                try {
                    rootWs.message(lkEvent)
                    APIGatewayV2HTTPResponse(200)
                } catch (e: Exception) {
                    try {
                        lkEvent.id.close()
                    } catch (e: Exception) { /*squish*/
                    }
                    handleWsDisconnect(event.requestContext.connectionId)
                    APIGatewayV2HTTPResponse(500, body = Serialization.json.encodeToString(e.message ?: ""))
                }
            }
        }
    }

    private suspend fun handleWsDisconnect(id: String): APIGatewayV2HTTPResponse {
        val lkEvent = WebSockets.DisconnectEvent(WebSocketIdentifier(wsType, id))
        return try {
            // Ensure it only runs once
            if (cache().setIfNotExists("${lkEvent.id}-closed", true, timeToLive = Duration.ofHours(1))) {
                rootWs.disconnect(lkEvent)
            }
            APIGatewayV2HTTPResponse(200)
        } catch (e: Exception) {
            APIGatewayV2HTTPResponse(500, body = Serialization.json.encodeToString(e.message ?: ""))
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
            (event.multiValueQueryStringParameters
                ?: mapOf()).entries.flatMap { it.value.map { v -> it.key to v.decodeURLPart() } }

        val match = Http.matcher.match(path, method) ?: run {
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
        val result = Http.execute(request).extensionForEngineAddCors(request).extensionForEngineCompression(request)
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

            b is HttpContent.Text -> {
                val response = withContext(Dispatchers.IO) {
                    APIGatewayV2HTTPResponse(
                        statusCode = result.status.code,
                        headers = outHeaders,
                        body = b.text()
                    )
                }
                return response
            }

            b is HttpContent.Binary -> {
                val response = withContext(Dispatchers.IO) {
                    APIGatewayV2HTTPResponse(
                        statusCode = result.status.code,
                        headers = outHeaders,
                        body = Base64.getEncoder().encodeToString(b.bytes),
                        isBase64Encoded = true
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