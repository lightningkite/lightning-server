package com.lightningkite.lightningserver.metrics

import com.lightningkite.lightningserver.core.serverEntryPoint
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.settings.setting
import com.lightningkite.lightningserver.tasks.Tasks
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.time.delay
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue

interface Metrics {
    val settings: MetricSettings
    suspend fun report(events: List<MetricEvent>)
    suspend fun clean() {}

    companion object {
        val main get() = metricsSettings
        val logger = LoggerFactory.getLogger(Metrics::class.java)
        val toReport = ConcurrentLinkedQueue<MetricEvent>()
        var shouldAllowAccess: suspend (HttpRequest) -> Boolean = { false }

        init {
            Tasks.onEngineReady {
                regularlyAndOnShutdown(Duration.ofMinutes(1)) {
                    logger.debug("Assembling metrics to report...")
                    val assembledData = ArrayList<MetricEvent>(toReport.size)
                    while (true) {
                        val item = toReport.poll() ?: break
                        assembledData.add(item)
                    }
                    logger.debug("Reporting ${assembledData.size} metric events to ${main()}...")
                    main().report(assembledData)
                    logger.debug("Report complete.")
                }
            }
        }

        suspend fun report(type: String, value: Double) {
            if (!Settings.sealed) return
            if (type in metricsSettings().settings.tracked)
                toReport.add(MetricEvent(type, serverEntryPoint()?.toString() ?: "Unknown", Instant.now(), value))
        }

        suspend fun <T> performance(type: String, action: suspend () -> T): T {
            val start = System.nanoTime()
            return try {
                action()
            } finally {
                report(type, (System.nanoTime() - start) / 1000000.0)
            }
        }

        suspend fun <T> handlerPerformance(handler: Any, action: suspend () -> T): T {
            return serverEntryPoint(handler) {
                performance("executionTime", action)
            }
        }
    }
}

val metricsSettings = setting("metrics", MetricSettings())
val metricsCleanSchedule = schedule("clearOldMetrics", Duration.ofHours(1)) {
    metricsSettings().clean()
}

@OptIn(DelicateCoroutinesApi::class)
inline fun regularlyAndOnShutdown(frequency: Duration, crossinline action: suspend () -> Unit) {
    GlobalScope.launch {
        while (true) {
            delay(frequency)
            action()
        }
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        Metrics.logger.info("Shutdown hook running...")
        runBlocking {
            action()
        }
    })
}
