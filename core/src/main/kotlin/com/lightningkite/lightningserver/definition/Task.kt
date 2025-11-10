package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.serialization.serializerOrContextual
import kotlinx.serialization.KSerializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Represents a background task that can be invoked with input data.
 *
 * Tasks are registered in [ServerBuilder] and can be executed asynchronously, either immediately
 * or queued for later execution depending on the server engine. They're useful for operations that
 * should run outside the request/response cycle, such as sending emails, processing uploads,
 * generating reports, or any long-running work that shouldn't block HTTP responses.
 *
 * The input type [INPUT] must be serializable so tasks can be queued and executed across processes
 * or server restarts in distributed environments.
 *
 * @param INPUT The type of input data this task accepts, must be serializable
 * @property serializer The serializer for the input type, used for task queuing
 * @property timeout Maximum duration this task is allowed to run before being cancelled. Defaults to 30 seconds.
 * @see StartupTask
 * @see ScheduledTask
 */
public interface Task<INPUT> {
    public val serializer: KSerializer<INPUT>
    public val timeout: Duration get() = 30.seconds

    context(server: ServerRuntime)
    public suspend fun executeInline(input: INPUT)
}

/**
 * Creates a [Task] with reified input type.
 *
 * This is the most convenient factory function, automatically deriving the serializer for the input type.
 *
 * @param INPUT The type of input data this task accepts
 * @param timeout Maximum duration allowed for task execution
 * @param handler The suspending function to execute, receiving the task instance and input data
 * @return A new [Task] instance
 */
public inline fun <reified INPUT> Task(
    timeout: Duration = 5.minutes,
    noinline handler: suspend context(ServerRuntime) Task<INPUT>.(INPUT) -> Unit
): Task<INPUT> = Task(serializerOrContextual<INPUT>(), timeout, handler)

/**
 * Creates a [Task] with an explicit serializer.
 *
 * Use this when you need explicit control over serialization, such as with polymorphic types.
 *
 * @param INPUT The type of input data this task accepts
 * @param input The serializer for the input type
 * @param timeout Maximum duration allowed for task execution
 * @param handler The suspending function to execute, receiving the task instance and input data
 * @return A new [Task] instance
 */
public fun <INPUT> Task(
    input: KSerializer<INPUT>,
    timeout: Duration = 5.minutes,
    handler: suspend context(ServerRuntime) Task<INPUT>.(INPUT) -> Unit
): Task<INPUT> =
    object : Task<INPUT> {
        override val timeout: Duration = timeout
        override val serializer: KSerializer<INPUT> = input

        context(server: ServerRuntime)
        override suspend fun executeInline(input: INPUT) {
            return handler(server, this, input)
        }
    }

/**
 * Launches this task with the given input in the context of a [ServerRuntime].
 *
 * The task execution behavior depends on the server engine - some engines execute tasks
 * immediately inline, while others queue them for background processing.
 *
 * @param input The input data for the task
 */
context(server: ServerRuntime)
public suspend fun <INPUT> Task<INPUT>.launch(input: INPUT): Unit = with(server) { invoke(input) }

/*
 * TODO: API Recommendations for Task.kt
 *
 * 1. The launch() extension calls invoke() on the Task, but Task doesn't define an invoke operator.
 *    This appears to be calling server.invoke(input) which suggests there's runtime lookup logic.
 *    Document this behavior or make the invocation path more explicit.
 *
 * 2. Add support for task retries on failure:
 *    - val maxRetries: Int
 *    - val retryDelay: Duration
 *    - fun shouldRetry(exception: Exception): Boolean
 *
 * 3. Consider adding task priority for queue-based execution:
 *    - enum class TaskPriority { LOW, NORMAL, HIGH, CRITICAL }
 *    - val priority: TaskPriority
 *
 * 4. Add lifecycle hooks for observability:
 *    - suspend fun onStart(input: INPUT)
 *    - suspend fun onComplete(input: INPUT)
 *    - suspend fun onFailure(input: INPUT, exception: Exception)
 *
 * 5. The default timeout of 5 minutes in the factory functions is different from the
 *    interface default of 30 seconds. This inconsistency could be confusing.
 *
 * 6. Add a way to cancel running tasks:
 *    - suspend fun cancel(reason: String)
 */