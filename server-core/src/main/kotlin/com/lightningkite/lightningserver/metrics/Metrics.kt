package com.lightningkite.lightningserver.metrics

import com.lightningkite.lightningserver.core.ServerEntryPointElement
import com.lightningkite.lightningserver.core.serverEntryPoint
import com.lightningkite.lightningserver.exceptions.report
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.settings.setting
import com.lightningkite.lightningserver.tasks.Tasks
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
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
            HealthStatus(HealthStatus.Level.OK, additionalMessage = "Available metrics: ${MetricType.known.map { it.name }.joinToString()}")
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
                com.lightningkite.lightningserver.engine.engine.backgroundReportingAction {
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

        fun report(type: MetricType, value: Double) {
            if (!Settings.sealed) return
            if (type.name in metricsSettings().settings.tracked)
                toReport.add(MetricEvent(type, null, Instant.now(), value))
        }

        suspend fun reportPerHandler(type: MetricType, value: Double) {
            if (!Settings.sealed) return
            if (type.name in metricsSettings().settings.tracked)
                toReport.add(MetricEvent(type, serverEntryPoint()?.toString() ?: "Unknown", Instant.now(), value))
        }

        suspend fun addToSumPerHandler(type: MetricType, value: Double) {
            coroutineContext[ServerEntryPointElement.Key]?.metricSums?.compute(type) { key, it -> (it ?: 0.0) + value }
        }

        suspend fun <T> addPerformanceToSumPerHandler(type: MetricType, countType: MetricType? = null, action: suspend () -> T): T {
            val start = System.nanoTime()
            val result = action()
            addToSumPerHandler(type, (System.nanoTime() - start) / 1000000.0)
            countType?.let { addToSumPerHandler(it, 1.0) }
            return result
        }

        suspend fun <T> performancePerHandler(type: MetricType, action: suspend () -> T): T {
            val start = System.nanoTime()
            val result = action()
            reportPerHandler(type, (System.nanoTime() - start) / 1000000.0)
            return result
        }

        suspend fun <T> handlerPerformance(handler: Any, action: suspend () -> T): T {
            return serverEntryPoint(handler) {
                val result = performancePerHandler(executionTime, action)
                try {
                    kotlin.coroutines.coroutineContext[ServerEntryPointElement.Key]?.metricSums?.forEach {
                        reportPerHandler(it.key, it.value)
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