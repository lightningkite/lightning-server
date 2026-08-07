package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Represents work that must be completed once per deploy, before the new version of the server
 * begins handling requests.
 *
 * A pre-deploy task runs concurrently with the still-live previous version of the server and is
 * gated by the deploy pipeline: the new version is not cut over until all pre-deploy tasks succeed,
 * and if any fails the deploy is aborted so the previous version keeps serving. Typical uses are
 * database schema/index reconciliation, backfills, and any preparation the new code depends on.
 *
 * Unlike [StartupTask] (which runs in every instance at boot, on the request-serving path),
 * pre-deploy tasks run exactly once per deploy in a dedicated invocation, off the serving path.
 * This keeps migration work out of cold starts and scale-outs.
 *
 * **Every pre-deploy task runs on every deploy** — the framework tracks no history. Tasks must
 * therefore be idempotent / convergent (safe to re-run). For the rare "run exactly once ever" case,
 * guard the work with a database-backed marker (e.g. `doOnce`) inside the task; because pre-deploy
 * runs once per deploy off the serving path, such a check is cheap and uncontended.
 *
 * Tasks are executed in dependency order - all dependencies of a task will complete before the task
 * itself runs. Tasks with no mutual dependencies may run concurrently. If any task fails, the whole
 * pre-deploy phase fails.
 *
 * @property dependencies Other pre-deploy tasks that must complete before this task can run,
 *                        supplied lazily so dependencies may reference tasks declared later or in
 *                        other modules without initialization-order hazards. Resolved once the
 *                        server definition is built.
 * @property timeout Maximum duration this task is allowed to run before being cancelled. Defaults to 5 minutes.
 * @see StartupTask
 * @see ScheduledTask
 * @see Task
 */
public interface PreDeployTask {
    public val dependencies: () -> Collection<PreDeployTask> get() = { emptyList() }
    public val timeout: Duration get() = 5.minutes

    context(server: ServerRuntime)
    public suspend fun execute()
}

/**
 * Creates a [PreDeployTask] with optional dependencies.
 *
 * @param dependencies Lazily-supplied pre-deploy tasks that must complete before this task runs
 * @param timeout Maximum duration allowed for task execution
 * @param handler The suspending function to execute, with access to [ServerRuntime]
 * @return A new [PreDeployTask] instance
 */
public fun PreDeployTask(
    dependencies: () -> Collection<PreDeployTask> = { emptyList() },
    timeout: Duration = 5.minutes,
    handler: suspend context(ServerRuntime) PreDeployTask.() -> Unit,
): PreDeployTask =
    object : PreDeployTask {
        override val timeout: Duration = timeout
        override val dependencies: () -> Collection<PreDeployTask> = dependencies

        context(server: ServerRuntime)
        override suspend fun execute() {
            return handler()
        }
    }

/**
 * Validates that there are no circular dependencies in the given pre-deploy tasks.
 *
 * Resolves each task's lazy dependency lambda and detects cycles via depth-first search.
 *
 * @param tasks Collection of pre-deploy tasks to validate
 * @throws IllegalStateException if a circular dependency is detected
 */
public fun validatePreDeployTaskDependencies(tasks: Collection<PreDeployTask>): Unit =
    validateDependencyGraph(tasks) { it.dependencies() }
