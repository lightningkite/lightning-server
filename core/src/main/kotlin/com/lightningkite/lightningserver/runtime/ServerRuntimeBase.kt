package com.lightningkite.lightningserver.runtime

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.runtime.invoke
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.services.SharedResources
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

public abstract class ServerRuntimeBase(override val server: ServerDefinition): ServerRuntime {
    override val settings: ServerSettings = ServerSettings(server.settings.plus(listOf(
        generalSettings,
        secretBasis
    )).toSet())
    override val internalSerialization: Serialization by lazy { Serialization(server.internalSerializersModule()) }
    override val externalSerialization: Serialization by lazy { Serialization(server.externalSerializersModule()) }
    override val sharedResources: SharedResources = SharedResources()
    override val projectName: String by lazy { generalSettings().projectName }
//    override val secretBasis: ByteArray by lazy { com.lightningkite.lightningserver.definition.secretBasis().bytes }

    protected suspend fun runStartupTasks(): Unit = coroutineScope {
        val taskToJob = server.startupTasks.values.associateWith { CompletableDeferred<Unit>() }
        server.startupTasks.entries.map { (location, task) ->
            launch {
                for(dep in task.dependencies) {
                    taskToJob[dep]!!.await()
                }
                try {
                    task.handleWithMetrics(location)
                    taskToJob[task]!!.complete(Unit)
                } catch(e: Exception) {
                    taskToJob[task]!!.completeExceptionally(e)
                }
            }
        }.joinAll()
    }

    init {
        (LoggerFactory.getILoggerFactory() as LoggerContext).apply {
            getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).apply {
                detachAndStopAllAppenders()
                level = Level.INFO
                addAppender(ConsoleAppender<ILoggingEvent>().apply {
                    context = LoggerFactory.getILoggerFactory() as LoggerContext
                    name = "XConsole"
                    encoder = PatternLayoutEncoder().apply {
                        context = LoggerFactory.getILoggerFactory() as LoggerContext
                        pattern = "%-12date{YYYY-MM-dd HH:mm:ss.SSS} %-5level %logger - %msg%n"
                        start()
                    }
                    start()
                })
//                addAppender(OpenTelemetryAppender().apply {
//                    context = LoggerFactory.getILoggerFactory() as LoggerContext
//                    name = "OpenTelemetry"
//                    start()
//                })
            }
        }
    }
}