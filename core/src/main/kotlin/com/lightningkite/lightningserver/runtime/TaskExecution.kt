@file:OptIn(InternalLightningServerApi::class)

package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.EngineApi
import com.lightningkite.lightningserver.OverrideOnly
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
 * Runs this task inline as a new execution caused by [from], with telemetry and interceptors.
 *
 * For running a task that was dispatched elsewhere, where [from] is the launching execution read back
 * from the queue. Inside an execution, use the overload without [from].
 */
@EngineApi
@OptIn(OverrideOnly::class)
context(engine: Engine)
public suspend fun <T> Task<T>.executeInlineWithMetrics(input: T, from: Execution): Unit =
    engine.executeTaskLike(
        TaskKind.Task,
        location,
        Execution.Task(id = Execution.ID.generate(), parent = from, location = location.asPathSegments()),
    ) { executeInline(input) }

/** Runs this task inline as a new execution caused by the current one, with telemetry and interceptors. */
context(runtime: ServerRuntime)
public suspend fun <T> Task<T>.executeInlineWithMetrics(input: T): Unit =
    @OptIn(EngineApi::class)
    executeInlineWithMetrics(input, runtime.execution)

/** Runs this scheduled task as a new root execution, with telemetry and interceptors. */
@EngineApi
@OptIn(OverrideOnly::class)
context(engine: Engine)
public suspend fun ScheduledTask.executeWithMetrics(): Unit =
    engine.executeTaskLike(
        TaskKind.Schedule,
        location,
        Execution.Schedule(id = Execution.ID.generate(), location = location.asPathSegments()),
    ) { execute() }

/** Runs this startup task as a new root execution, with telemetry and interceptors. */
@EngineApi
@OptIn(OverrideOnly::class)
context(engine: Engine)
public suspend fun StartupTask.executeWithMetrics(): Unit =
    engine.executeTaskLike(
        TaskKind.Startup,
        location,
        Execution.Startup(id = Execution.ID.generate(), location = location.asPathSegments()),
    ) { execute() }

/** Runs this pre-deploy task as a new root execution, with telemetry and interceptors. */
@EngineApi
@OptIn(OverrideOnly::class)
context(engine: Engine)
public suspend fun PreDeployTask.executeWithMetrics(): Unit =
    engine.executeTaskLike(
        TaskKind.PreDeploy,
        location,
        Execution.PreDeploy(id = Execution.ID.generate(), location = location.asPathSegments()),
    ) { execute() }
