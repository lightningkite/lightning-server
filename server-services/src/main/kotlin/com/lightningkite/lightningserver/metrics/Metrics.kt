package com.lightningkite.lightningserver.metrics

import com.lightningkite.lightningserver.reporting.ServerEntryPointElement
import com.lightningkite.lightningserver.reporting.serverEntryPoint
import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import kotlinx.datetime.Clock
import com.lightningkite.now
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlinx.datetime.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.hours

interface Metrics: HealthCheckable {
    val settings: MetricSettings
    suspend fun report(events: List<MetricEvent>)
    fun queue(event: MetricEvent)
    suspend fun clean() {}

    companion object {
        val healthCheckMetric = MetricType("Health Checks Run", MetricUnit.Count)
        val executionTime = MetricType("Execution Time", MetricUnit.Milliseconds)
    }

    override suspend fun healthCheck(): HealthStatus {
        return try {
            report(
                listOf(
                    MetricEvent(
                        metricType = healthCheckMetric,
                        entryPoint = serverEntryPoint().toString(),
                        time = now(),
                        value = 1.0
                    )
                )
            )
            HealthStatus(HealthStatus.Level.OK, additionalMessage = "Available metrics: ${MetricType.known.map { it.name }.joinToString()}")
        } catch(e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }

    fun report(type: MetricType, value: Double) {
        if (type.name in settings.tracked)
            queue(MetricEvent(type, null, now(), value))
    }

    suspend fun reportPerHandler(type: MetricType, value: Double) {
        if (type.name in settings.tracked)
            queue(MetricEvent(type, serverEntryPoint()?.toString() ?: "Unknown", now(), value))
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
            kotlin.coroutines.coroutineContext[ServerEntryPointElement.Key]?.metricSums?.forEach {
                reportPerHandler(it.key, it.value)
            }
            result
        }
    }
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