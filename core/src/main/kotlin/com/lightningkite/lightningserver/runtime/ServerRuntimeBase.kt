package com.lightningkite.lightningserver.runtime

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.services.OpenTelemetry
import com.lightningkite.services.SharedResources
import com.lightningkite.services.otel.applyToLogback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

public abstract class ServerRuntimeBase(override val server: ServerDefinition): ServerRuntime {
    override val settings: ServerSettings = ServerSettings(server.settings.plus(listOf(
        generalSettings,
        secretBasis,
        telemetrySettings,
        loggingSettings,
    )).toSet())
    override val internalSerialization: Serialization by lazy { Serialization(server.internalSerializersModule()) }
    override val externalSerialization: Serialization by lazy { Serialization(server.externalSerializersModule()) }
    override val sharedResources: SharedResources = SharedResources()
    override val projectName: String by lazy { generalSettings().projectName }
    override val openTelemetry: OpenTelemetry? by lazy {
        telemetrySettings()
    }
//    override val secretBasis: ByteArray by lazy { com.lightningkite.lightningserver.definition.secretBasis().bytes }

    protected suspend fun runStartupTasks(): Unit = coroutineScope {
        val taskToJob = server.startupTasks.values.associateWith { CompletableDeferred<Unit>() }
        server.startupTasks.entries.map { (location, task) ->
            launch {
                for(dep in task.dependencies) {
                    taskToJob[dep]!!.await()
                }
                try {
                    task.executeWithMetrics(location)
                    taskToJob[task]!!.complete(Unit)
                } catch(e: Exception) {
                    taskToJob[task]!!.completeExceptionally(e)
                }
            }
        }.joinAll()
    }
}