@file:OptIn(InternalLightningServerApi::class)

package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.definition.PreDeployTask
import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey

// Pre-allocated TelemetryKey instances (backend caches by equality).
private val taskType = TelemetryKey.OfString("task.type")
private val taskRoute = TelemetryKey.OfString("task.route")

/**
 * The location a task-like execution is registered under, as an initiator can hold it.
 *
 * Tasks, schedules, startup and pre-deploy tasks are all registered under all-constant paths, so the
 * segments are the whole of their location.
 */
private fun PathSpec0.asPathSegments(): PathSegments = PathSegments.parse(toString())

private enum class TaskKind(
    val label: String,
    val telemetryType: String,
) {
    Task("task", "TASK"),
    Schedule("schedule", "SCHEDULE"),
    // Startup and pre-deploy have no launcher and no request anywhere in their ancestry, so they
    // derive their own anchor and there is nothing to pass.
    Startup("startup", "STARTUP"),
    PreDeploy("predeploy", "PREDEPLOY"),
}

/**
 * Mints the execution for one task-like run, instruments it, and runs [body] inside it.
 *
 * The four kinds differ only in the three facts [TaskKind] holds and in what they invoke, so this is
 * the whole of what "run a task" means; the public entry points below exist to name the receiver each
 * kind is invoked on.
 *
 * @param execution The execution this run is attributed to — a freshly minted [Execution.Task] parented
 *   to whatever launched it, or a self-parented [Execution.Schedule]/[Execution.Startup]/[Execution.PreDeploy]
 *   for the three kinds the server starts itself.
 */
private suspend fun Engine.executeTaskLike(
    kind: TaskKind,
    location: PathSpec0,
    execution: Execution,
    body: suspend context(ServerRuntime) () -> Unit,
) {
    execute("${kind.label} $location", execution, TelemetryAttributes {
        put(taskType, kind.telemetryType)
        put(taskRoute, location.toString())
    }) {
        body()
    }
}

/**
 * Executes a task with telemetry metrics.
 *
 * @param location The path specification for this task
 * @param input The input parameter for the task
 * @param cause The execution that launched this task, or null when nothing launched it, such as a
 *   manual invocation.
 */
context(engine: Engine)
public suspend fun <T> Task<T>.executeInlineWithMetrics(location: PathSpec0, input: T, from: Execution): Unit =
    engine.executeTaskLike(
        TaskKind.Task,
        location,
        Execution.Task(
            id = Execution.ID.generate(),
            causedBy = from.id,
            rootExecution = from.rootExecution,
            location = location.asPathSegments(),
        ),
    ) { executeInline(input) }

/**
 * Executes a scheduled task with telemetry metrics.
 *
 * @param location The path specification for this scheduled task
 */
context(engine: Engine)
public suspend fun ScheduledTask.executeWithMetrics(location: PathSpec0): Unit =
    engine.executeTaskLike(
        TaskKind.Schedule,
        location,
        Execution.Schedule(id = Execution.ID.generate(), location = location.asPathSegments()),
    ) { execute() }

/**
 * Executes a startup task with telemetry metrics.
 *
 * @param location The path specification for this startup task
 */
context(engine: Engine)
public suspend fun StartupTask.executeWithMetrics(location: PathSpec0): Unit =
    engine.executeTaskLike(
        TaskKind.Startup,
        location,
        Execution.Startup(id = Execution.ID.generate(), location = location.asPathSegments()),
    ) { execute() }

/**
 * Executes a pre-deploy task with telemetry metrics.
 *
 * @param location The path specification for this pre-deploy task
 */
context(engine: Engine)
public suspend fun PreDeployTask.executeWithMetrics(location: PathSpec0): Unit =
    engine.executeTaskLike(
        TaskKind.PreDeploy,
        location,
        Execution.PreDeploy(id = Execution.ID.generate(), location = location.asPathSegments()),
    ) { execute() }
