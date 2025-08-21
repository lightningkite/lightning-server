package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.exceptionSettings
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.definition.metricsSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.runtime.invoke
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.services.MetricReporter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

public abstract class ServerRuntimeBase(override val server: ServerDefinition): ServerRuntime {
    override val internalSerialization: Serialization = Serialization(server.internalSerializersModule)
    override val externalSerialization: Serialization = Serialization(server.externalSerializersModule)

    override val settings: ServerSettings = ServerSettings(server.settings.plus(listOf(
        generalSettings,
        metricsSettings,
        exceptionSettings,
        secretBasis
    )).toSet())
    override val metrics: MetricReporter by lazy { metricsSettings() }
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
}