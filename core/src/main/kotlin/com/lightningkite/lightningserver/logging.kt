package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.definition.MutableExtensions
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import java.util.logging.Logger

private object LoggerKey : MutableExtensions.Key<Logger> {
    fun default(): Logger = Logger.getLogger("com.lightningkite.lightningserver")
}

public var ServerBuilder.logger: Logger
    get() = extensions[LoggerKey] ?: LoggerKey.default()
    set(value) { extensions[LoggerKey] = value }

public val ServerDefinition.logger: Logger
    get() = extensions[LoggerKey] ?: LoggerKey.default()

public val ServerRuntime.logger: Logger get() = server.logger