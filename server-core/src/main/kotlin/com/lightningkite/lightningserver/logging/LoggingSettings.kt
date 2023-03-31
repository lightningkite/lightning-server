package com.lightningkite.lightningserver.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import com.lightningkite.lightningserver.settings.setting
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * LoggingSettings configures what the logging of the server should look like.
 * This has a lot of customizability though it can be complicated.
 * You can tell it what files to log to, if logs get printed to the console, and what packages you want to log.
 * This uses ch.qos.logback:logback-classic, so you can reference its docs for custom file patterns.
 *
 * @param default will log everything from all packages unless specified otherwise.
 * @param logger is where you can be more specific logging for certain packages. Additive will state if default should also log that package still.
 */
@Serializable
data class LoggingSettings(
    val default: ContextSettings? = ContextSettings(null, true, "INFO", false),
    val logger: Map<String, ContextSettings>? = null
) {
    @Serializable
    data class ContextSettings(
        val filePattern: String? = "build/logs/logfile-%d{yyyy-MM-dd}.log",
        val toConsole: Boolean = false,
        val level: String = "INFO",
        val additive: Boolean = false,
    ) {
        fun apply(partName: String, to: LogbackLogger) {
            to.isAdditive = additive
            to.level = Level.toLevel(level)
            if (filePattern?.isNotBlank() == true) {
                to.addAppender(RollingFileAppender<ILoggingEvent>().apply rolling@{
                    context = LoggerFactory.getILoggerFactory() as LoggerContext
                    name = partName
                    encoder = PatternLayoutEncoder().apply {
                        context = LoggerFactory.getILoggerFactory() as LoggerContext
                        pattern = "%-12date{YYYY-MM-dd HH:mm:ss.SSS} %-5level %logger - %msg%n"
                        start()
                    }
                    isAppend = true
                    rollingPolicy = TimeBasedRollingPolicy<ILoggingEvent>().apply {
                        context = LoggerFactory.getILoggerFactory() as LoggerContext
                        setParent(this@rolling);
                        fileNamePattern = filePattern;
                        maxHistory = 7;
                        start();
                    }
                    start()
                })
            }
            if (toConsole) {
                to.addAppender(ConsoleAppender<ILoggingEvent>().apply {
                    context = LoggerFactory.getILoggerFactory() as LoggerContext
                    name = partName + "Console"
                    encoder = PatternLayoutEncoder().apply {
                        context = LoggerFactory.getILoggerFactory() as LoggerContext
                        pattern = "%-12date{YYYY-MM-dd HH:mm:ss.SSS} %-5level %logger - %msg%n"
                        start()
                    }
                    start()
                })
            }
        }
    }

    init {
        val logCtx: LoggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        logCtx.getLogger(Logger.ROOT_LOGGER_NAME).detachAndStopAllAppenders()
        default?.apply(Logger.ROOT_LOGGER_NAME, logCtx.getLogger(Logger.ROOT_LOGGER_NAME))
        for (sub in (logger ?: mapOf())) {
            sub.value.apply(sub.key, logCtx.getLogger(sub.key))
        }
    }
}

val loggingSettings = setting("logging", LoggingSettings())