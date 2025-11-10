package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.serialization.KSerializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Represents a task that executes once during server initialization.
 *
 * Startup tasks are registered in [ServerBuilder] and executed automatically when the server starts.
 * They are useful for initialization work like database migrations, cache warming, health checks,
 * or other one-time setup operations that must complete before the server begins handling requests.
 *
 * Tasks are executed in dependency order - all dependencies of a task will complete before the task
 * itself runs. Tasks with no mutual dependencies may run concurrently.
 *
 * @property dependencies Other startup tasks that must complete before this task can run.
 *                        Use this to establish execution order when tasks depend on each other.
 * @property timeout Maximum duration this task is allowed to run before being cancelled. Defaults to 30 seconds.
 * @see ScheduledTask
 * @see Task
 */
public interface StartupTask {
    public val dependencies: Collection<StartupTask> get() = emptyList()
    public val timeout: Duration get() = 30.seconds

    context(server: ServerRuntime)
    public suspend fun execute()
}

/**
 * Creates a [StartupTask] with optional dependencies.
 *
 * @param dependencies Other startup tasks that must complete before this task runs
 * @param timeout Maximum duration allowed for task execution
 * @param handler The suspending function to execute at startup, with access to [ServerRuntime]
 * @return A new [StartupTask] instance
 */
public fun StartupTask(
    dependencies: Collection<StartupTask> = emptyList(),
    timeout: Duration = 5.minutes,
    handler: suspend context(ServerRuntime) () -> Unit
): StartupTask =
    object : StartupTask {
        override val timeout: Duration = timeout
        override val dependencies: Collection<StartupTask> = dependencies

        context(server: ServerRuntime)
        override suspend fun execute() {
            return handler(server)
        }
    }

/*
 * TODO: API Recommendations for StartupTask.kt
 *
 * 1. **POTENTIAL ISSUE**: Circular dependencies are not detected or prevented.
 *    Consider adding validation to detect dependency cycles at server build time.
 *
 * 2. Add failure handling options:
 *    - enum class FailureBehavior { FAIL_STARTUP, LOG_AND_CONTINUE, RETRY }
 *    - val failureBehavior: FailureBehavior
 *    Currently a failed startup task likely crashes the server.
 *
 * 3. Add task naming for better logging/debugging:
 *    - val name: String
 *    This would help identify which task failed during startup.
 *
 * 4. Consider adding priority within the same dependency level:
 *    - val priority: Int
 *    For tasks with no dependencies, determines execution order.
 *
 * 5. The default timeout of 5 minutes in the factory function is different from the
 *    interface default of 30 seconds. This inconsistency could be confusing.
 *
 * 6. Add lifecycle hooks:
 *    - suspend fun onComplete()
 *    - suspend fun onFailure(exception: Exception)
 *    For observability and cleanup.
 *
 * 7. Consider adding conditional execution:
 *    - suspend fun shouldExecute(): Boolean
 *    Allows skipping tasks based on environment or configuration.
 */