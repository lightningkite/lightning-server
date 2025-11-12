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
 * @property timeout Maximum duration this task is allowed to run before being cancelled. Defaults to 5 minutes.
 * @see ScheduledTask
 * @see Task
 */
public interface StartupTask {
    public val dependencies: Collection<StartupTask> get() = emptyList()
    public val timeout: Duration get() = 5.minutes

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

/**
 * Validates that there are no circular dependencies in the given startup tasks.
 *
 * Uses depth-first search to detect cycles in the dependency graph.
 *
 * @param tasks Collection of startup tasks to validate
 * @throws IllegalStateException if a circular dependency is detected
 */
public fun validateStartupTaskDependencies(tasks: Collection<StartupTask>) {
    if (tasks.isEmpty()) return

    val visiting = mutableSetOf<StartupTask>()
    val visited = mutableSetOf<StartupTask>()

    fun dfs(task: StartupTask, path: List<StartupTask>) {
        if (task in visiting) {
            // Found a cycle
            val cycleStart = path.indexOf(task)
            val cycle = path.subList(cycleStart, path.size) + task
            val cycleDescription = cycle.joinToString(" -> ") {
                it.toString().substringAfterLast('.').substringBefore('@')
            }
            throw IllegalStateException(
                "Circular dependency detected in startup tasks: $cycleDescription"
            )
        }
        if (task in visited) return

        visiting.add(task)
        for (dep in task.dependencies) {
            dfs(dep, path + task)
        }
        visiting.remove(task)
        visited.add(task)
    }

    for (task in tasks) {
        if (task !in visited) {
            dfs(task, emptyList())
        }
    }
}

/*
 * TODO: API Recommendations for StartupTask.kt
 *
 * 1. Add failure handling options:
 *    - enum class FailureBehavior { FAIL_STARTUP, LOG_AND_CONTINUE, RETRY }
 *    - val failureBehavior: FailureBehavior
 *    Currently a failed startup task likely crashes the server.
 *
 * 2. Add task naming for better logging/debugging:
 *    - val name: String
 *    This would help identify which task failed during startup.
 *
 * 3. Consider adding priority within the same dependency level:
 *    - val priority: Int
 *    For tasks with no dependencies, determines execution order.
 *
 * 4. Add lifecycle hooks:
 *    - suspend fun onComplete()
 *    - suspend fun onFailure(exception: Exception)
 *    For observability and cleanup.
 *
 * 5. Consider adding conditional execution:
 *    - suspend fun shouldExecute(): Boolean
 *    Allows skipping tasks based on environment or configuration.
 */