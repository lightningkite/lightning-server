package com.lightningkite.lightningserver.aws

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import com.lightningkite.lightningserver.core.Disconnectable
import com.lightningkite.lightningserver.core.serverLogger
import com.lightningkite.lightningserver.encryption.OpenSsl
import com.lightningkite.lightningserver.engine.Engine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.exceptions.report
import com.lightningkite.lightningserver.http.Http
import com.lightningkite.lightningserver.serialization.InternalCommunicationEncoding
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.settings.SettingsSerializer
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.tasks.Task
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.websocket.WebSockets
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
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
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import kotlin.system.exitProcess


abstract class AwsAdapter(val communicationEncoding: InternalCommunicationEncoding = InternalCommunicationEncoding.JavaData) : RequestStreamHandler, Resource {

    val logger: Logger = LoggerFactory.getLogger(AwsAdapter::class.java)
    var preventLambdaTimeoutReuse: Boolean = false

    init {
        logger.debug("Initializing AwsAdapter...")
    }

    val http = AwsAdapterHttp(this)
    val ws = AwsAdapterWs(this)
    val tasks = AwsAdapterTask(this)
    val schedules = AwsAdapterSchedule(this)

    fun loadSettings() {
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
        Serialization.Internal.json.decodeFromString(SettingsSerializer(), decryptedBytes.toString(Charsets.UTF_8))

        logger.debug("Running Tasks.onSettingsReady()...")
        runBlocking { Tasks.onSettingsReady() }
        logger.debug("Tasks.onSettingsReady() complete.")
        configureEngine
        Http.matcher
        WebSockets.matcher
        Core.getGlobalContext().register(this)
    }

    val region by lazy { Region.of(System.getenv("AWS_REGION")) }
    val lambdaClient = LambdaAsyncClient.builder()
        .region(region)
        .httpClient(AwsConnections.asyncClient)
        .overrideConfiguration(AwsConnections.clientOverrideConfiguration)
        .build()
    val apiGatewayManagement by lazy {
        ApiGatewayManagementApiAsyncClient.builder()
            .region(region)
            .httpClient(AwsConnections.asyncClient)
            .overrideConfiguration(AwsConnections.clientOverrideConfiguration)
            .endpointOverride(URI.create("https://" + generalSettings().wsUrl.removePrefix("wss://")))
            .build()
    }

    private val backgroundReportingActions = ArrayList<suspend () -> Unit>()
    private val configureEngine by lazy {
        engine = object : Engine {
            override val internalCommunicationEncoding: InternalCommunicationEncoding  get() = communicationEncoding
            override suspend fun <T> publish(topic: String, serializer: KSerializer<T>, output: T) = ws.publish(topic, serializer, output)
            override suspend fun launchTask(task: Task<Any?>, input: Any?) = tasks.launchTask(task, input)

            override fun backgroundReportingAction(action: suspend () -> Unit) {
                backgroundReportingActions.add(action)
            }
        }
        logger.debug("Running Tasks.onEngineReady()...")
        runBlocking { Tasks.onEngineReady() }
        logger.debug("Tasks.onEngineReady() complete.")
        Unit
    }

    override fun beforeCheckpoint(context: org.crac.Context<out Resource>?) {
        logger.debug("beforeCheckpoint() - Preparing DynamoDB...")
        runBlocking {
            ws.webSocketDynamo.ready.await()
        }
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
        var roughContext: String = "???"
        try {
            val asJson = Serialization.json.parseToJsonElement(input.reader().readText()) as JsonObject
            val response: APIGatewayV2HTTPResponse = blockingTimeout(context.remainingTimeInMillis - 5_000L) {
                when {
                    asJson.containsKey("taskName") -> tasks.handleTask(
                        Serialization.Internal.json.decodeFromJsonElement(
                            AwsAdapterTask.TaskInvoke.serializer(),
                            asJson
                        ).also { roughContext = it.taskName }
                    )

                    asJson.containsKey("httpMethod") -> http.handleHttp(
                        Serialization.json.decodeFromJsonElement<APIGatewayV2HTTPEvent>(
                            asJson
                        ).also { roughContext = it.httpMethod + " " + it.path }
                    ) { roughContext = it }

                    asJson.containsKey("storage") -> ws.handleWebsocketDidConnect(
                        Serialization.json.decodeFromJsonElement<AwsAdapterWs.WebSocketDidConnect>(
                            asJson
                        ).also { roughContext = "DIDCONNECT" }
                    )

                    asJson["requestContext"]?.jsonObject?.containsKey("connectionId") == true -> ws.handleWebsocket(
                        Serialization.json.decodeFromJsonElement<APIGatewayV2WebsocketRequest>(asJson)
                            .also { roughContext = "Websocket" }
                    )

                    asJson.containsKey("scheduled") -> {
                        val parsed: AwsAdapterSchedule.Scheduled = Serialization.json.decodeFromJsonElement(asJson)
                        roughContext = parsed.scheduled
                        schedules.handleSchedule(parsed)
                    }

                    asJson.containsKey("topic") -> {
                        val parsed: AwsAdapterWs.WebSocketPublish = Serialization.json.decodeFromJsonElement(asJson)
                        roughContext = parsed.topic
                        ws.publishHandler(parsed)
                    }

                    else -> {
                        serverLogger.error("Unrecognized message: ${asJson}")
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
                ex.report(roughContext)
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
                ex.report(roughContext)
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
                    e.report(roughContext)
                }
            } catch (t: Throwable) { /*squish*/
            }
            println("Killing self to prevent potentially broken reuse due to full VirtualMachineError ${e.message}.")
            exitProcess(1)
        }
    }
}
