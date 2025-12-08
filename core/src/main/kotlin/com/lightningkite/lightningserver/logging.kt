package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.definition.MutableExtensions
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Extension key for storing a KLogger instance in server extensions.
 * Provides a default logger if none is explicitly configured.
 */
private object LoggerKey : MutableExtensions.Key<KLogger> {
    fun default(): KLogger = KotlinLogging.logger("com.lightningkite.lightningserver")
}

/**
 * Gets or sets the logger for this ServerBuilder.
 * Defaults to a logger named "com.lightningkite.lightningserver" if not explicitly set.
 */
public var ServerBuilder.logger: KLogger
    get() = extensions[LoggerKey] ?: LoggerKey.default()
    set(value) { extensions[LoggerKey] = value }

/**
 * Gets the logger for this ServerDefinition.
 * Defaults to a logger named "com.lightningkite.lightningserver" if not explicitly set.
 */
public val ServerDefinition.logger: KLogger
    get() = extensions[LoggerKey] ?: LoggerKey.default()

/**
 * Gets the logger for this ServerRuntime, delegating to the underlying server definition's logger.
 */
public val ServerRuntime.logger: KLogger get() = server.logger