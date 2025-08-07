package com.lightningkite.lightningserver

import com.lightningkite.services.MetricSink
import com.lightningkite.services.SettingContext
import kotlinx.serialization.modules.SerializersModule

public fun ServerRuntime.settingContext(name: String): SettingContext = object: SettingContext {
    override val name: String get() = name
    override val serializersModule: SerializersModule get() = this@settingContext.server.internalSerialization.serializersModule
    override val metricSink: MetricSink get() = TODO()
    override val secretBasis: ByteArray get() = TODO()

}