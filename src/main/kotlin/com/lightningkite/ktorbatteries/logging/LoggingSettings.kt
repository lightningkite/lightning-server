package com.lightningkite.ktorbatteries.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import com.lightningkite.ktorbatteries.SettingSingleton
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File


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

    }

    companion object : SettingSingleton<LoggingSettings>()

    init {
        instance = this
        val logCtx: LoggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        if (default == null)
            logCtx.getLogger(Logger.ROOT_LOGGER_NAME).detachAndStopAllAppenders()
        logCtx.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender("console")
        default?.apply(Logger.ROOT_LOGGER_NAME, logCtx.getLogger(Logger.ROOT_LOGGER_NAME))
        for (sub in (logger ?: mapOf())) {
            sub.value.apply(sub.key, logCtx.getLogger(sub.key))
        }
    }
}