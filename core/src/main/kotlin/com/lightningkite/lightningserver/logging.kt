package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.definition.MutableExtensions
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging

private object LoggerKey : MutableExtensions.Key<KLogger> {
    fun default(): KLogger = KotlinLogging.logger("com.lightningkite.lightningserver")
}

public var ServerBuilder.logger: KLogger
    get() = extensions[LoggerKey] ?: LoggerKey.default()
    set(value) { extensions[LoggerKey] = value }

public val ServerDefinition.logger: KLogger
    get() = extensions[LoggerKey] ?: LoggerKey.default()

public val ServerRuntime.logger: KLogger get() = server.logger