package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.services.telemetry.TelemetryBackend
import com.lightningkite.services.SharedResources
import kotlinx.coroutines.*

/**
 * Base implementation of [ServerRuntime] providing common functionality.
 *
 * This abstract class handles:
 * - Settings initialization and management (including automatic addition of system settings)
 * - Serialization setup for both internal and external use
 * - Shared resources management
 * - Metrics backend integration
 * - Startup task execution with dependency resolution
 *
 * Subclasses should implement:
 * - [ServerRuntime.sendWebSocketSubscriptionMessage]
 * - [ServerRuntime.Task.invoke]
 * - [ServerRuntime.serverId]
 * - [ServerRuntime.serverVersion]
 * - HTTP request handling (typically via an engine)
 * - Scheduled task execution
 *
 * @param server The server definition to run
 */
public abstract class ServerRuntimeBase(override val server: ServerDefinition) : ServerRuntime {
    /**
     * Settings manager with automatically included system settings.
     *
     * Adds generalSettings, secretBasis, telemetrySettings, and loggingSettings
     * to the server's defined settings.
     */
    override val settings: ServerSettings = ServerSettings(server) + setOf(
        generalSettings,
        secretBasis,
        telemetrySettings,
        loggingSettings,
    )

    /**
     * Serialization for internal use (database, caching, etc.).
     *
     * Lazily initialized from the server's internal serializers module.
     */
    override val internalSerialization: Serialization by lazy { Serialization(server.internalSerializersModule()) }

    /**
     * Serialization for external API communication (HTTP bodies, etc.).
     *
     * Lazily initialized from the server's external serializers module.
     */
    override val externalSerialization: Serialization by lazy { Serialization(server.externalSerializersModule()) }

    /**
     * Shared resources manager for services that need cleanup on shutdown.
     */
    override val sharedResources: SharedResources = SharedResources()

    /**
     * Project name from general settings.
     */
    override val projectName: String by lazy { generalSettings().projectName }

    /**
     * Metrics backend for distributed tracing and RED metrics.
     *
     * Lazily initialized from telemetry settings. Defaults to [TelemetryBackend.Noop] when
     * telemetry is not configured so all metrics calls are zero-overhead no-ops.
     */
    override val telemetryBackend: TelemetryBackend by lazy {
        telemetrySettings()
    }

    /**
     * Runs a set of tasks respecting their dependency order, failing fast.
     *
     * Each task waits for its dependencies to complete before running; tasks with no mutual
     * dependencies run concurrently. This uses structured concurrency: if any task throws, the
     * surrounding scope cancels the remaining tasks and rethrows, so a single failure aborts the
     * whole phase rather than being silently swallowed.
     *
     * @param tasks The tasks to run, keyed by their location.
     * @param dependenciesOf Resolves the direct dependencies of a task.
     * @param runOne Executes a single task (typically with telemetry).
     */
    private suspend fun <T> runTaskGraph(
        tasks: Map<PathSpec0, T>,
        dependenciesOf: (T) -> Collection<T>,
        runOne: suspend (PathSpec0, T) -> Unit,
    ): Unit = coroutineScope {
        val taskToJob = tasks.values.associateWith { CompletableDeferred<Unit>() }
        tasks.entries.forEach { (location, task) ->
            launch {
                for (dep in dependenciesOf(task)) {
                    (taskToJob[dep]
                        ?: throw IllegalStateException("A task dependency was marked but is not registered to the server: $dep")).await()
                }
                // Any throw here propagates out of the coroutineScope, cancelling siblings (fail-fast).
                runOne(location, task)
                // This can't be null - see taskToJob definition in connection with the loop above.
                taskToJob.getValue(task).complete(Unit)
            }
        }
        // coroutineScope joins all children and rethrows the first failure.
    }

    /**
     * Executes all startup tasks respecting their dependency order, failing fast.
     *
     * Called by subclasses during per-instance server initialization, before the instance begins
     * serving. If any startup task fails, startup fails.
     *
     * @throws Exception if any startup task fails
     */
    protected suspend fun runStartupTasks(): Unit =
        runTaskGraph(server.startupTasks, { it.dependencies }) { location, task -> task.executeWithMetrics(location) }

    /**
     * Executes all pre-deploy tasks respecting their dependency order, failing fast.
     *
     * Intended to be invoked once per deploy, in a dedicated `predeploy` run (not while serving),
     * before the new version is cut over. Every registered pre-deploy task runs every time - the
     * framework tracks no history. If any task fails, the whole pre-deploy phase fails, which the
     * deploy pipeline uses to abort the cutover.
     *
     * @throws Exception if any pre-deploy task fails
     */
    protected suspend fun runPreDeployTasks(): Unit =
        runTaskGraph(server.preDeployTasks, { it.dependencies() }) { location, task -> task.executeWithMetrics(location) }
}

/*
 * TODO: API Recommendations for ServerRuntimeBase.kt
 *
 * 2. The runStartupTasks() method launches all tasks concurrently but doesn't limit concurrency.
 *    For servers with many startup tasks, this could create resource contention.
 *    Consider adding a concurrency limit or sequential execution option.
 *
 * 3. Startup task failures don't provide context about which task failed or the dependency chain.
 *    Consider wrapping exceptions with more context before rethrowing.
 *
 * 4. The lazy initialization of serialization could fail with an unclear error if the module
 *    returns null or throws. Consider eager initialization with better error messages.
 *
 * 5. Settings are automatically augmented with system settings (general, secret, telemetry, logging).
 *    If user code defines settings with the same names, they'll be silently overridden by the toSet().
 *    Consider detecting conflicts and throwing an error, or documenting the override behavior.
 *
 * 6. SharedResources is created but never cleaned up in this base class. Subclasses should call
 *    sharedResources.close() on shutdown, but there's no enforcement. Consider adding a cleanup method.
 *
 * 7. The settings list is deduplicated by toSet() but ServerSetting equality is based on object identity
 *    (data class), so settings with the same name but different instances won't be deduplicated.
 *    This could lead to duplicate settings. Consider using distinctBy { it.name }.
 */