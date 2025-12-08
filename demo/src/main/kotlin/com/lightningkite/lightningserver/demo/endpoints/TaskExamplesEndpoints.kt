package com.lightningkite.lightningserver.demo.endpoints

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.route
import com.lightningkite.services.cache.Cache
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * TaskExamplesEndpoints - Demonstrates background task and scheduled job patterns.
 *
 * These examples showcase:
 * - Background task execution
 * - Task queuing and async processing
 * - Scheduled/periodic jobs
 * - Long-running operations
 * - Task status tracking
 * - Integration with cache for task coordination
 */
class TaskExamplesEndpoints(
    private val cache: Runtime<Cache>
) : ServerBuilder() {
    
    /**
     * Background task for sending emails.
     * Demonstrates: Task definition with typed input
     */
    val sendEmailTask = path.path("tasks").path("send-email") bind Task { input: SendEmailTaskInput ->
        val taskId = Uuid.random()
        println("[$taskId] Starting email send task for ${input.to}")

        // Store task status in cache
        cache().set("task:$taskId:status", "processing", String.serializer(), 1.minutes)

        try {
            // Simulate email sending
            delay(2000)

            println("[$taskId] Email sent successfully to ${input.to}")
            cache().set("task:$taskId:status", "completed", String.serializer(), 1.minutes)
            cache().set("task:$taskId:result", "Email sent successfully", String.serializer(), 1.minutes)
        } catch (e: Exception) {
            println("[$taskId] Email send failed: ${e.message}")
            cache().set("task:$taskId:status", "failed", String.serializer(), 1.minutes)
            cache().set("task:$taskId:error", e.message ?: "Unknown error", String.serializer(), 1.minutes)
        }
    }
    
    /**
     * Background task for data processing.
     * Demonstrates: Long-running background operation
     */
    val processDataTask = path.path("tasks").path("process-data") bind Task { input: ProcessDataTaskInput ->
        val taskId = Uuid.random()
        println("[$taskId] Starting data processing task for ${input.items.size} items")

        var processed = 0
        input.items.forEach { item ->
            delay(100) // Simulate processing
            processed++

            // Update progress in cache
            val progress = (processed.toDouble() / input.items.size * 100).toInt()
            cache().set("task:$taskId:progress", progress, Int.serializer(), 5.minutes)

            if (processed % 10 == 0) {
                println("[$taskId] Progress: $progress%")
            }
        }

        println("[$taskId] Data processing completed: $processed items")
        cache().set("task:$taskId:status", "completed", String.serializer(), 5.minutes)
        cache().set("task:$taskId:result", "Processed $processed items", String.serializer(), 5.minutes)
    }
    
    /**
     * POST /tasks/enqueue-email
     *
     * Enqueue an email sending task.
     * Demonstrates: Triggering background tasks from HTTP endpoints
     */
    val enqueueEmailTask = path.path("tasks").path("enqueue-email").post bind ApiHttpHandler(
        summary = "Enqueue an email sending task",
        description = "Creates a background task to send an email asynchronously",
        auth = noAuth,
        successCode = HttpStatus.Accepted,
        implementation = { input: SendEmailTaskInput ->
            // Invoke the task asynchronously
            sendEmailTask.executeInline(input)

            TaskEnqueuedResponse(
                message = "Email task enqueued successfully",
                taskType = "send-email",
                estimatedCompletionSeconds = 5
            )
        }
    )
    
    /**
     * POST /tasks/enqueue-processing
     *
     * Enqueue a data processing task.
     */
    val enqueueProcessingTask = path.path("tasks").path("enqueue-processing").post bind ApiHttpHandler(
        summary = "Enqueue a data processing task",
        description = "Creates a background task to process a batch of data items",
        auth = noAuth,
        successCode = HttpStatus.Accepted,
        implementation = { input: ProcessDataTaskInput ->
            processDataTask.executeInline(input)

            TaskEnqueuedResponse(
                message = "Data processing task enqueued successfully",
                taskType = "process-data",
                estimatedCompletionSeconds = (input.items.size * 0.1).toInt()
            )
        }
    )
    
    /**
     * Scheduled task that runs every 5 minutes.
     * Demonstrates: Periodic background jobs
     */
    val cleanupScheduledTask = path.path("tasks").path("scheduled-cleanup") bind ScheduledTask(frequency = 5.minutes) {
        println("Running scheduled cleanup task...")

        // Simulate cleanup work
        delay(1000)

        val cleaned = Random.nextInt(1, 100)
        println("Cleanup completed: removed $cleaned old entries")

        // Update metrics in cache
        cache().set("cleanup:last-run", System.currentTimeMillis(), Long.serializer(), 1.minutes)
        cache().set("cleanup:last-count", cleaned, Int.serializer(), 1.minutes)
    }
    
    /**
     * Scheduled task that runs every minute for health checks.
     * Demonstrates: Frequent periodic tasks
     */
    @Serializable
    data class HealthStatus(
        val timestamp: Long,
        val status: String,
        val uptime: Long
    )

    val healthCheckScheduledTask = path.path("tasks").path("scheduled-health-check") bind ScheduledTask(frequency = 1.minutes) {
        println("Running health check...")

        // Simulate health check
        delay(500)

        val status = HealthStatus(
            timestamp = System.currentTimeMillis(),
            status = "healthy",
            uptime = Random.nextLong(1000, 100000)
        )

        cache().set("health:status", status, HealthStatus.serializer(), 2.minutes)
        println("Health check completed: ${status.status}")
    }
    
    /**
     * GET /tasks/demo/trigger-background
     *
     * Demonstrates triggering a simple background task.
     */
    val triggerBackgroundTask = path.path("tasks").path("demo").path("trigger-background").get bind HttpHandler {
        // Create and invoke a simple background task
        val taskNumber = Random.nextInt(1, 1000)

        // Simulate background work
        GlobalScope.launch {
            delay(3000)
            println("Background task $taskNumber completed!")
            cache().set("task:$taskNumber:result", "Completed", String.serializer(), 1.minutes)
        }

        HttpResponse.plainText("Background task #$taskNumber started!")
    }
    
    /**
     * GET /tasks/scheduled/status
     *
     * Get status of scheduled tasks.
     * Demonstrates: Monitoring scheduled job execution
     */
    val scheduledTaskStatus = path.path("tasks").path("scheduled").path("status").get bind ApiHttpHandler(
        summary = "Get scheduled task status",
        description = "Retrieves execution status and metrics for scheduled tasks",
        auth = noAuth,
        successCode = HttpStatus.OK,
        implementation = { _: Unit ->
            val cleanupLastRun = cache().get("cleanup:last-run", Long.serializer())
            val cleanupLastCount = cache().get("cleanup:last-count", Int.serializer())
            val healthStatus = cache().get("health:status", HealthStatus.serializer())

            ScheduledTaskStatusResponse(
                cleanup = ScheduledTaskInfo(
                    name = "cleanup",
                    frequency = "5 minutes",
                    lastRunTimestamp = cleanupLastRun,
                    lastRunResult = "Cleaned $cleanupLastCount entries"
                ),
                healthCheck = ScheduledTaskInfo(
                    name = "health-check",
                    frequency = "1 minute",
                    lastRunTimestamp = healthStatus?.timestamp,
                    lastRunResult = healthStatus?.status
                )
            )
        }
    )
}

// Task input/output models

@Serializable
data class SendEmailTaskInput(
    val to: String,
    val subject: String,
    val body: String
)

@Serializable
data class ProcessDataTaskInput(
    val items: List<String>,
    val operation: String = "process"
)

@Serializable
data class TaskEnqueuedResponse(
    val message: String,
    val taskType: String,
    val estimatedCompletionSeconds: Int
)

@Serializable
data class ScheduledTaskInfo(
    val name: String,
    val frequency: String,
    val lastRunTimestamp: Long?,
    val lastRunResult: String?
)

@Serializable
data class ScheduledTaskStatusResponse(
    val cleanup: ScheduledTaskInfo,
    val healthCheck: ScheduledTaskInfo
)
