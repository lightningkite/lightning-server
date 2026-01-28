package com.lightningkite.lightningserver.runtime

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.lightningserver.telemetry.HttpMetrics
import com.lightningkite.services.OpenTelemetry
import com.lightningkite.services.SharedResources
import com.lightningkite.services.otel.applyToLogback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Base implementation of [ServerRuntime] providing common functionality.
 *
 * This abstract class handles:
 * - Settings initialization and management (including automatic addition of system settings)
 * - Serialization setup for both internal and external use
 * - Shared resources management
 * - OpenTelemetry integration
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
public abstract class ServerRuntimeBase(override val server: ServerDefinition): ServerRuntime {
    /**
     * Settings manager with automatically included system settings.
     *
     * Adds generalSettings, secretBasis, telemetrySettings, and loggingSettings
     * to the server's defined settings.
     */
    override val settings: ServerSettings = ServerSettings(server.settings.plus(listOf(
        generalSettings,
        secretBasis,
        telemetrySettings,
        loggingSettings,
    )).toSet())

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
     * OpenTelemetry instance for distributed tracing and metrics.
     *
     * Lazily initialized from telemetry settings.
     */
    override val openTelemetry: OpenTelemetry? by lazy {
        telemetrySettings()
    }

    /**
     * HTTP metrics for OpenTelemetry.
     *
     * Lazily initialized when first accessed. Returns null if telemetry is not configured.
     * Provides metrics for:
     * - Request duration (histogram)
     * - Request count (counter)
     * - Response status category (counter)
     * - Server errors (counter)
     */
    public val httpMetrics: HttpMetrics? by lazy {
        openTelemetry?.let { HttpMetrics(it.getMeter("com.lightningkite.lightningserver.http")) }
    }

    /**
     * Executes all startup tasks respecting their dependency order.
     *
     * Tasks are launched concurrently but will wait for their dependencies to complete
     * before starting. If any task fails, its exception propagates to dependent tasks.
     *
     * This method should be called by subclasses during server initialization.
     *
     * @throws Exception if any startup task fails
     */
    protected suspend fun runStartupTasks(): Unit = coroutineScope {
        val taskToJob = server.startupTasks.values.associateWith { CompletableDeferred<Unit>() }
        server.startupTasks.entries.map { (location, task) ->
            launch {
                for(dep in task.dependencies) {
                    (taskToJob[dep] ?: throw IllegalStateException("Dependency '${dep.location}' marked, but not registered to server.")).await()
                }
                // This can't ever be null - see taskToJob definition in connection with loop.
                val job = taskToJob[task]!!
                try {
                    task.executeWithMetrics(location)
                    job.complete(Unit)
                } catch(e: Exception) {
                    job.completeExceptionally(e)
                }
            }
        }.joinAll()
    }
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