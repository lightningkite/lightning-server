@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningserver.engine.awsserverless

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntimeBase
import com.lightningkite.lightningserver.settings.SettingsSerializer
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.services.Service
import com.lightningkite.services.aws.AwsConnections
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.jsonObject
import org.crac.Core
import org.crac.Resource
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiAsyncClient
import software.amazon.awssdk.services.lambda.LambdaAsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import kotlin.system.exitProcess


public open class AwsAdapter(server: ServerDefinition) : ServerRuntimeBase(server), RequestStreamHandler, Resource {

    internal val logger: Logger = LoggerFactory.getLogger(this::class.java)
    internal var preventLambdaTimeoutReuse: Boolean = false

    init {
        logger.debug("Initializing AwsAdapter...")
    }

    internal val http = AwsAdapterHttp(this)
    internal val ws = AwsAdapterWs(this)
    internal val tasks = AwsAdapterTask(this)
    internal val schedules = AwsAdapterSchedule(this)

    internal fun loadSettings() {
        logger.debug("Loading settings...")
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

    internal fun loadSettings(bytes: ByteArray) {
        val decryptedBytes = System.getenv("LIGHTNING_SERVER_SETTINGS_DECRYPTION")
            ?.takeIf { it.isNotBlank() }
            ?.let { sha256Password ->
                OpenSsl.decryptAesCbcPkcs5Sha256(bytes, sha256Password.toByteArray())
            }
            ?: bytes
        this.settings.serializable.putAll(
            internalSerialization.json.decodeFromString(
                SettingsSerializer(server.settings),
                decryptedBytes.toString(Charsets.UTF_8)
            )
        )
        runBlocking { runStartupTasks() }
        Core.getGlobalContext().register(this)
    }

    internal val region by lazy { Region.of(System.getenv("AWS_REGION")) }
    internal val lambdaClient = LambdaAsyncClient.builder()
        .region(region)
        .httpClient(AwsConnections.asyncClient)
        .overrideConfiguration(AwsConnections.clientOverrideConfiguration)
        .build()
    internal val apiGatewayManagement by lazy {
        ApiGatewayManagementApiAsyncClient.builder()
            .region(region)
            .httpClient(AwsConnections.asyncClient)
            .overrideConfiguration(AwsConnections.clientOverrideConfiguration)
            .endpointOverride(URI.create("https://" + generalSettings().wsUrl.removePrefix("wss://")))
            .build()
    }

    private val backgroundReportingActions = ArrayList<suspend () -> Unit>()

    override suspend fun <T> Locationed<PathSpec0, Task<T>>.invoke(input: T) {
        tasks.launchTask(this, input)
    }

    override val serverId: String
        get() =  System.getenv("AWS_LAMBDA_LOG_STREAM_NAME")
    override val serverVersion: String
        get() = System.getenv("AWS_LAMBDA_FUNCTION_VERSION")

    override suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(event: WebSocketSubscriptionMessage<PATH, T>) {
        ws.publish(event.path.toString(internalSerialization.stringArrayFormat), event.topic.type, event.value)
    }

    override fun beforeCheckpoint(context: org.crac.Context<out Resource>?) {
        logger.debug("beforeCheckpoint() - Preparing DynamoDB...")
        runBlocking {
            ws.webSocketDynamo.ready.await()
        }
        logger.debug("beforeCheckpoint() - Preparing all connections...")
        runBlocking {
            settings.allGoals(this@AwsAdapter).entries.forEachConcurrent {
                (it.value as? Service)?.let {
                    logger.debug("Initially connecting to ${it.name}...")
                    it.connect()
                    logger.debug("Initially connected to ${it.name}. Now disconnecting...")
                    it.disconnect()
                    logger.debug("Disconnected ${it.name}.")
                }
            }
        }
        logger.debug("Initialization complete.")
    }

    override fun afterRestore(context: org.crac.Context<out Resource>?) {
        logger.debug("afterRestore() - opening all connections")
        runBlocking {
            settings.allGoals(this@AwsAdapter).entries.forEachConcurrent {
                (it.value as? Service)?.let {
                    logger.debug("Connecting ${it.name}...")
                    runBlocking { it.connect() }
                    logger.debug("Connected ${it.name}.")
                }
            }
        }
        logger.debug("Connections Complete")
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

            @OptIn(DelicateCoroutinesApi::class)
            val backgroundRegularHealthActionsJob = GlobalScope.launch {
                println("Running ${backgroundReportingActions.size} backgroundRegularHealthActions...")
                try {
                    metricsSettings().flush()
                } catch (e: Exception) {
                    exceptionSettings().report(e, "METRICS")
                }
            }
            internalSerialization.json.encodeToStream(response, output)
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
                exceptionSettings().report(e, roughContext)
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
                exceptionSettings().report(e, roughContext)
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
                    exceptionSettings().report(e, roughContext)
                }
            } catch (t: Throwable) { /*squish*/
            }
            println("Killing self to prevent potentially broken reuse due to full VirtualMachineError ${e.message}.")
            exitProcess(1)
        }
    }
}
