package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.definition.metricsSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.runtime.invoke
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.services.MetricSink

public abstract class ServerRuntimeBase(override val server: ServerDefinition): ServerRuntime {
    override val internalSerialization: Serialization = Serialization(server.internalSerializersModule)
    override val externalSerialization: Serialization = Serialization(server.externalSerializersModule)

    override val settings: ServerSettings = ServerSettings(server.settings.plus(listOf(
        generalSettings,
        metricsSettings,
        secretBasis
    )).toSet())
    override val metrics: MetricSink by lazy { metricsSettings() }
    override val projectName: String by lazy { generalSettings().projectName }
//    override val secretBasis: ByteArray by lazy { com.lightningkite.lightningserver.definition.secretBasis().bytes }
}