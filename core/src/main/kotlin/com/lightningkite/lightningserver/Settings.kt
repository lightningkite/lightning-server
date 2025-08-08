package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.definition.metrics
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.MetricSink
import com.lightningkite.services.SettingContext
import kotlinx.serialization.modules.SerializersModule

public fun ServerRuntime.settingContext(name: String): SettingContext = object: SettingContext {
    override val name: String get() = name
    override val serializersModule: SerializersModule get() = this@settingContext.internalSerialization.serializersModule
    override val metricSink: MetricSink get() = this@settingContext.server.metrics()
    override val secretBasis: ByteArray get() = this@settingContext.server.secretBasis().bytes

}