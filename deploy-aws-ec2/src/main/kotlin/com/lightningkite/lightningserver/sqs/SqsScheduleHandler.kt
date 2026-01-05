package com.lightningkite.lightningserver.sqs

import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.executeWithMetrics
import com.lightningkite.services.Untested
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Message format for scheduled task events from EventBridge via SQS.
 */
@Serializable
public data class ScheduledTaskMessage(
    val scheduled: String
)

/**
 * Handles scheduled tasks by polling an SQS queue.
 *
 * When deployed to EC2 with Auto Scaling, multiple instances may be running.
 * SQS ensures that each message is processed by only one instance (visibility timeout).
 *
 * Usage:
 * ```kotlin
 * fun main() {
 *     val server = Server.build()
 *     val runtime = NettyEngine(server)
 *
 *     // Start SQS handler if queue URL is configured
 *     System.getenv("SQS_SCHEDULE_QUEUE_URL")?.let { queueUrl ->
 *         SqsScheduleHandler(
 *             queueUrl = queueUrl,
 *             runtime = runtime
 *         ).start()
 *     }
 *
 *     // Start HTTP server
 *     runtime.start()
 * }
 * ```
 *
 * @param queueUrl The SQS queue URL (from Terraform output or environment variable)
 * @param runtime The server runtime (engine) to use for executing tasks
 * @param sqsClient Optional SQS client (defaults to creating one)
 * @param visibilityTimeout How long a message is hidden from other consumers while being processed
 * @param pollInterval How long to wait between poll attempts when queue is empty
 * @param maxMessages Maximum messages to receive per poll (1-10)
 */
@Untested
public class SqsScheduleHandler(
    private val queueUrl: String,
    private val runtime: ServerRuntime,
    private val sqsClient: SqsAsyncClient = SqsAsyncClient.create(),
    private val visibilityTimeout: Duration = 5.minutes,
    private val pollInterval: Duration = 20.seconds,
    private val maxMessages: Int = 10,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var running = false
    private var job: Job? = null

    /**
     * Starts polling the SQS queue in a background coroutine.
     * Returns immediately - polling happens asynchronously.
     *
     * @param scope CoroutineScope to run in (defaults to a new IO scope)
     */
    public fun start(scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())): Job {
        if (running) {
            throw IllegalStateException("SqsScheduleHandler is already running")
        }
        running = true

        job = scope.launch {
            println("SqsScheduleHandler: Starting to poll $queueUrl")
            while (isActive && running) {
                try {
                    pollAndProcess()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    System.err.println("SqsScheduleHandler: Error polling SQS: ${e.message}")
                    e.printStackTrace()
                    delay(pollInterval)
                }
            }
            println("SqsScheduleHandler: Stopped polling")
        }

        return job!!
    }

    /**
     * Stops the SQS polling loop gracefully.
     */
    public fun stop() {
        running = false
        job?.cancel()
    }

    private suspend fun pollAndProcess() {
        val request = ReceiveMessageRequest.builder()
            .queueUrl(queueUrl)
            .maxNumberOfMessages(maxMessages)
            .visibilityTimeout(visibilityTimeout.inWholeSeconds.toInt())
            .waitTimeSeconds(pollInterval.inWholeSeconds.toInt())  // Long polling
            .build()

        val response = sqsClient.receiveMessage(request).await()

        for (message in response.messages()) {
            try {
                processMessage(message)
                deleteMessage(message)
            } catch (e: Exception) {
                System.err.println("SqsScheduleHandler: Failed to process message ${message.messageId()}: ${e.message}")
                e.printStackTrace()
                // Message will become visible again after visibility timeout
            }
        }
    }

    private suspend fun processMessage(message: Message) {
        val body = message.body()

        // EventBridge wraps the input in a detail field, but we send direct JSON
        val taskMessage = try {
            json.decodeFromString<ScheduledTaskMessage>(body)
        } catch (e: Exception) {
            // Try parsing as EventBridge event format
            try {
                val eventBridgeMessage = json.decodeFromString<EventBridgeEvent>(body)
                eventBridgeMessage.detail
            } catch (e2: Exception) {
                // Don't log full message body to avoid leaking sensitive data
                System.err.println("SqsScheduleHandler: Failed to parse message (length=${body.length}): ${e.message}")
                throw e
            }
        }

        val scheduledPath = taskMessage.scheduled
        println("SqsScheduleHandler: Processing scheduled task: $scheduledPath")

        val path = PathSpec0.fromString(scheduledPath)
        val schedule = runtime.server.schedules[path]
        if (schedule == null) {
            System.err.println("SqsScheduleHandler: Unknown scheduled task: $scheduledPath")
            return
        }

        // Execute the scheduled task with the server runtime context
        with(runtime) {
            schedule.executeWithMetrics(path)
        }
        println("SqsScheduleHandler: Completed scheduled task: $scheduledPath")
    }

    private suspend fun deleteMessage(message: Message) {
        val request = DeleteMessageRequest.builder()
            .queueUrl(queueUrl)
            .receiptHandle(message.receiptHandle())
            .build()

        sqsClient.deleteMessage(request).await()
    }

    @Serializable
    private data class EventBridgeEvent(
        val detail: ScheduledTaskMessage
    )
}

/**
 * Extension to await a CompletableFuture from the AWS SDK.
 */
private suspend fun <T> CompletableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    this.whenComplete { result, error ->
        if (error != null) {
            cont.resumeWithException(error)
        } else {
            cont.resume(result)
        }
    }
    cont.invokeOnCancellation {
        this.cancel(true)
    }
}
