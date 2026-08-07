package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

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
    handler: suspend context(ServerRuntime) StartupTask.() -> Unit,
): StartupTask =
    object : StartupTask {
        override val timeout: Duration = timeout
        override val dependencies: Collection<StartupTask> = dependencies

        context(server: ServerRuntime)
        override suspend fun execute() {
            return handler()
        }
    }

/**
 * Validates that there are no circular dependencies in a task dependency graph.
 *
 * Uses depth-first search to detect cycles. Shared by [StartupTask] and [PreDeployTask]; the
 * [dependencies] accessor supplies each task's direct dependencies (for [PreDeployTask] this
 * resolves the lazy dependency lambda).
 *
 * @param tasks Collection of tasks to validate
 * @param dependencies Accessor returning the direct dependencies of a task
 * @throws IllegalStateException if a circular dependency is detected
 */
public fun <T> validateDependencyGraph(tasks: Collection<T>, dependencies: (T) -> Collection<T>) {
    if (tasks.isEmpty()) return

    val visiting = mutableSetOf<T>()
    val visited = mutableSetOf<T>()

    fun dfs(task: T, path: List<T>) {
        if (task in visiting) {
            // Found a cycle
            val cycleStart = path.indexOf(task)
            val cycle = path.subList(cycleStart, path.size) + task
            val cycleDescription = cycle.joinToString(" -> ") {
                it.toString().substringAfterLast('.').substringBefore('@')
            }
            throw IllegalStateException(
                "Circular dependency detected in tasks: $cycleDescription"
            )
        }
        if (task in visited) return

        visiting.add(task)
        for (dep in dependencies(task)) {
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

/**
 * Validates that there are no circular dependencies in the given startup tasks.
 *
 * @param tasks Collection of startup tasks to validate
 * @throws IllegalStateException if a circular dependency is detected
 */
public fun validateStartupTaskDependencies(tasks: Collection<StartupTask>): Unit =
    validateDependencyGraph(tasks) { it.dependencies }