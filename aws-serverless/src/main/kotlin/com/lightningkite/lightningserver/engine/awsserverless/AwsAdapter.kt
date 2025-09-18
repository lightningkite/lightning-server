@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningserver.engine.awsserverless

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.include
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.path
import com.lightningkite.lightningserver.runtime.ServerRuntimeBase
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.lightningserver.settings.SettingsSerializer
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.services.Service
import com.lightningkite.services.aws.AwsConnections
import com.lightningkite.services.get
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.jsonObject
import org.crac.Core
import org.crac.Resource
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.amazon.awssdk.identity.spi.internal.DefaultAwsCredentialsIdentity
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiAsyncClient
import software.amazon.awssdk.services.apigatewaymanagementapi.model.DeleteConnectionRequest
import software.amazon.awssdk.services.apigatewaymanagementapi.model.DeleteConnectionResponse
import software.amazon.awssdk.services.apigatewaymanagementapi.model.PostToConnectionRequest
import software.amazon.awssdk.services.apigatewaymanagementapi.model.PostToConnectionResponse
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.lambda.LambdaAsyncClient
import software.amazon.awssdk.services.lambda.model.InvokeRequest
import software.amazon.awssdk.services.lambda.model.InvokeResponse
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import kotlin.system.exitProcess


public open class AwsAdapter(server: ServerDefinition) : ServerRuntimeBase(server), RequestStreamHandler, Resource {

    internal val logger: KLogger = KotlinLogging.logger("com.lightningkite.lightningserver.engine.awsserverless")
    internal var preventLambdaTimeoutReuse: Boolean = false

    override val settings: ServerSettings = ServerSettings(super.settings.settings.plus(awsLambdaRuntimeSettings).toSet())

    init {
        logger.info { "Initializing AwsAdapter..." }
    }

    internal val http = AwsAdapterHttp(this)
    internal val ws = AwsAdapterWs(this)
    internal val tasks = AwsAdapterTask(this)
    internal val schedules = AwsAdapterSchedule(this)

    init { loadSettings() }
    protected open fun loadSettings() {
        fun loadSettings(bytes: ByteArray) {
            val decryptedBytes = System.getenv("LIGHTNING_SERVER_SETTINGS_DECRYPTION")
                ?.takeIf { it.isNotBlank() }
                ?.let { sha256Password ->
                    OpenSsl.decryptAesCbcPkcs5Sha256(bytes, sha256Password.toByteArray())
                }
                ?: bytes
            println("Raw settings: ${decryptedBytes.toString(Charsets.UTF_8)}")
            this.settings.include(
                internalSerialization.json.decodeFromString(
                    SettingsSerializer(settings.settings.toList()),
                    decryptedBytes.toString(Charsets.UTF_8)
                ).also { println("Parsed settings: $it") }
            )
            this.settings.ready()
            runBlocking { runStartupTasks() }
            Core.getGlobalContext().register(this)
            println("loadSettings: generalSettings = ${generalSettings()}")
        }
        logger.info { "Loading settings..." }
        System.getenv("LIGHTNING_SERVER_SETTINGS_SECRET_ID")?.let { secretId ->
            SecretsManagerClient.create().getSecretValue {
                it.secretId(secretId)
            }.secretString().let { loadSettings(it.toByteArray()) }
        }
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

    protected open val region: Region by lazy { Region.of(System.getenv("AWS_REGION")) }

    private val lambdaClient = LambdaAsyncClient.builder()
        .region(region)
        .httpClient(get(AwsConnections).asyncClient)
        .overrideConfiguration(get(AwsConnections).clientOverrideConfiguration)
        .build()
    public open suspend fun invokeLambda(invokeRequest: InvokeRequest): InvokeResponse {
        return lambdaClient.invoke(invokeRequest).await()
    }
    private val apiGatewayManagement by lazy {
        println("apiGatewayManagement: generalSettings = ${generalSettings()}")
        ApiGatewayManagementApiAsyncClient.builder()
            .region(region)
            .httpClient(get(AwsConnections).asyncClient)
            .overrideConfiguration(get(AwsConnections).clientOverrideConfiguration)
            .endpointOverride(URI.create("https://".plus(generalSettings().wsUrl.removePrefix("wss://")).also {
                logger.info { "Connecting to WebSocket at '$it'" }
            }))
            .build()
    }
    public open suspend fun apiGatewayWsPostToConnection(request: PostToConnectionRequest): PostToConnectionResponse {
        try {
            return apiGatewayManagement.postToConnection(request).await()
        } catch(e: Exception) {
            logger.error { "Failed to send a web socket message" }
            throw e
        }
    }
    public open suspend fun apiGatewayWsDeleteConnection(request: DeleteConnectionRequest): DeleteConnectionResponse {
        return apiGatewayManagement.deleteConnection(request).await()
    }
    public open val dynamo: DynamoDbAsyncClient by lazy {
        DynamoDbAsyncClient.builder()
            .region(region)
            .httpClient(get(AwsConnections).asyncClient)
            .overrideConfiguration(get(AwsConnections).clientOverrideConfiguration)
            .build()
    }


    private val backgroundReportingActions = ArrayList<suspend () -> Unit>()

    override suspend fun <T> Task<T>.invoke(input: T) {
        tasks.launchTask(location, this, input)
    }

    override val serverId: String
        get() =  System.getenv("AWS_LAMBDA_LOG_STREAM_NAME")
    override val serverVersion: String
        get() = System.getenv("AWS_LAMBDA_FUNCTION_VERSION")

    override suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(event: WebSocketSubscriptionMessage<PATH, T>) {
        ws.publish(event.path(internalSerialization.stringArrayFormat), event.topic.type, event.value)
    }

    override fun beforeCheckpoint(context: org.crac.Context<out Resource>?) {
        logger.info { "beforeCheckpoint() - Preparing DynamoDB..." }
        runBlocking {
            ws.webSocketDynamo.ready.await()
        }
        logger.info { "beforeCheckpoint() - Preparing all connections..." }
        runBlocking {
            settings.allGoals().entries.forEachConcurrent {
                (it.value as? Service)?.let {
                    logger.debug("Initially connecting to ${it.name}...")
                    it.connect()
                    logger.debug("Initially connected to ${it.name}. Now disconnecting...")
                    it.disconnect()
                    logger.debug("Disconnected ${it.name}.")
                }
            }
        }
        logger.info { "Initialization complete." }
    }

    override fun afterRestore(context: org.crac.Context<out Resource>?) {
        logger.info { "afterRestore() - opening all connections" }
        runBlocking {
            settings.allGoals().entries.forEachConcurrent {
                (it.value as? Service)?.let {
                    logger.debug("Connecting ${it.name}...")
                    runBlocking { it.connect() }
                    logger.debug("Connected ${it.name}.")
                }
            }
        }
        logger.info { "Connections Complete" }
    }

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context): Unit = runBlocking {
        var roughContext: String = "???"
        try {
            val asJson = internalSerialization.json.parseToJsonElement(input.reader().readText()) as JsonObject
            val response: APIGatewayV2HTTPResponse = withTimeout(context.remainingTimeInMillis - 5_000L) {
                when {
                    asJson.containsKey("taskName") -> tasks.handleTask(
                        internalSerialization.json.decodeFromJsonElement(
                            AwsAdapterTask.TaskInvoke.serializer(),
                            asJson
                        ).also { roughContext = "AwsHandler" + it.taskName }
                    )

                    asJson.containsKey("httpMethod") -> http.handleHttp(
                        internalSerialization.json.decodeFromJsonElement<APIGatewayV2HTTPEvent>(
                            asJson
                        ).also { roughContext = "AwsHandler" + it.httpMethod + " " + it.path }
                    ) { roughContext = "AwsHandler" + it }

                    asJson.containsKey("storage") -> ws.handleWebsocketDidConnect(
                        internalSerialization.json.decodeFromJsonElement<AwsAdapterWs.WebSocketDidConnect>(
                            asJson
                        ).also { roughContext = "AwsHandler" + "DIDCONNECT" }
                    )

                    asJson["requestContext"]?.jsonObject?.containsKey("connectionId") == true -> ws.handleWebsocket(
                        internalSerialization.json.decodeFromJsonElement<APIGatewayV2WebsocketRequest>(asJson)
                            .also { roughContext = "AwsHandler" + "Websocket" }
                    )

                    asJson.containsKey("scheduled") -> {
                        val parsed: AwsAdapterSchedule.Scheduled =
                            internalSerialization.json.decodeFromJsonElement(asJson)
                        roughContext = "AwsHandler" + parsed.scheduled
                        schedules.handleSchedule(parsed)
                    }

                    asJson.containsKey("topic") -> {
                        val parsed: AwsAdapterWs.WebSocketPublish =
                            internalSerialization.json.decodeFromJsonElement(asJson)
                        roughContext = "AwsHandler" + parsed.topic
                        ws.publishHandler(parsed)
                    }

                    else -> {
                        logger.error("Unrecognized message: ${asJson}")
                        APIGatewayV2HTTPResponse(
                            statusCode = 500,
                            body = "No response available for the handler"
                        )
                    }
                }
            }

            internalSerialization.json.encodeToStream(response, output)
            output.flush()
            output.close()
        } catch (e: Exception) {
            // Something basic in processing died, we must report it.
            runBlocking {
                logger.error("Full lambda failure", e)
            }
            if (preventLambdaTimeoutReuse) {
                println("Killing self to prevent potentially broken reuse.  To disable this, set AwsAdapter.preventLambdaTimeoutReuse to false.")
                exitProcess(1)
            }
        } catch (e: StackOverflowError) {
            // StackOverflowError is bad, but not critical.  This lambda could still serve other requests.
            runBlocking {
                logger.error("Full lambda failure", e)
            }
            if (preventLambdaTimeoutReuse) {
                println("Killing self to prevent potentially broken reuse.  To disable this, set AwsAdapter.preventLambdaTimeoutReuse to false.")
                exitProcess(1)
            }
        } catch (e: VirtualMachineError) {
            // If we have a critical error, we need to make sure the process dies so Lambda doesn't attempt to reuse the VM.
            try {
                runBlocking {
                    logger.error("Full lambda failure", e)
                }
            } catch (t: Throwable) { /*squish*/
            }
            println("Killing self to prevent potentially broken reuse due to full VirtualMachineError ${e.message}.")
            exitProcess(1)
        }
    }
}
