package com.lightningkite.lightningserver

import com.lightningkite.serviceabstractions.MetricSink
import com.lightningkite.serviceabstractions.SettingContext
import kotlinx.serialization.modules.SerializersModule

public fun ServerRunning.settingContext(name: String): SettingContext = object: SettingContext {
    override val name: String get() = name
    override val serializersModule: SerializersModule get() = this@settingContext.server.internalSerialization.serializersModule
    override val metricSink: MetricSink get() = TODO()
    override val secretBasis: ByteArray get() = TODO()

}