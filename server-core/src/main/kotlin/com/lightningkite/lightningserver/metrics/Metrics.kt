package com.lightningkite.lightningserver.metrics

import com.lightningkite.lightningserver.core.ServerEntryPointElement
import com.lightningkite.lightningserver.core.serverEntryPoint
import com.lightningkite.lightningserver.exceptions.report
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.settings.setting
import com.lightningkite.lightningserver.tasks.Tasks
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.time.delay
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

interface Metrics: HealthCheckable {
    val settings: MetricSettings
    suspend fun report(events: List<MetricEvent>)
    suspend fun clean() {}

    override suspend fun healthCheck(): HealthStatus {
        return try {
            report(
                listOf(
                    MetricEvent(
                        metricType = healthCheckMetric,
                        entryPoint = serverEntryPoint().toString(),
                        time = Instant.now(),
                        value = 1.0
                    )
                )
            )
            HealthStatus(HealthStatus.Level.OK)
        } catch(e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }

    companion object {
        val main get() = metricsSettings
        val logger = LoggerFactory.getLogger(Metrics::class.java)
        val toReport = ConcurrentLinkedQueue<MetricEvent>()

        val healthCheckMetric = MetricType("Health Checks Run", MetricUnit.Count)
        val executionTime = MetricType("Execution Time", MetricUnit.Milliseconds)

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

        suspend fun report(type: MetricType, value: Double) {
            if (!Settings.sealed) return
            if (type.name in metricsSettings().settings.tracked)
                toReport.add(MetricEvent(type, serverEntryPoint()?.toString() ?: "Unknown", Instant.now(), value))
        }

        suspend fun addToSum(type: MetricType, value: Double) {
            coroutineContext[ServerEntryPointElement.Key]?.metricSums?.compute(type) { key, it -> (it ?: 0.0) + value }
        }

        suspend fun <T> addPerformanceToSum(type: MetricType, countType: MetricType? = null, action: suspend () -> T): T {
            val start = System.nanoTime()
            return try {
                action()
            } finally {
                addToSum(type, (System.nanoTime() - start) / 1000000.0)
                countType?.let { addToSum(type, 1.0) }
            }
        }

        suspend fun <T> performance(type: MetricType, action: suspend () -> T): T {
            val start = System.nanoTime()
            return try {
                action()
            } finally {
                report(type, (System.nanoTime() - start) / 1000000.0)
            }
        }

        suspend fun <T> handlerPerformance(handler: Any, action: suspend () -> T): T {
            return serverEntryPoint(handler) {
                val result = performance(executionTime, action)
                try {
                    kotlin.coroutines.coroutineContext[ServerEntryPointElement.Key]?.metricSums?.forEach {
                        report(it.key, it.value)
                    }
                } catch(e: Exception) {
                    e.report()
                }
                result
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


@Serializable
data class MetricType(
    val name: String,
    val unit: MetricUnit
) {
    companion object {
        private val _known = HashSet<MetricType>()
        val known: Set<MetricType> get() = _known
    }
    init {
        _known.add(this)
    }
}

@Serializable
enum class MetricUnit {
    Seconds,
    Microseconds,
    Milliseconds,
    Bytes,
    Kilobytes,
    Megabytes,
    Gigabytes,
    Terabytes,
    Bits,
    Kilobits,
    Megabits,
    Gigabits,
    Terabits,
    Percent,
    Count,
    BytesPerSecond,
    KilobytesPerSecond,
    MegabytesPerSecond,
    GigabytesPerSecond,
    TerabytesPerSecond,
    BitsPerSecond,
    KilobitsPerSecond,
    MegabitsPerSecond,
    GigabitsPerSecond,
    TerabitsPerSecond,
    CountPerSecond,
    Other,
}