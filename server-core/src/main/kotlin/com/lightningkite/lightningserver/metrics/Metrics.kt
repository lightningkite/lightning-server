package com.lightningkite.lightningserver.metrics

import com.lightningkite.lightningserver.core.serverEntryPoint
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
import kotlin.math.min

interface Metrics {
    suspend fun report(events: List<MetricEvent>)
    companion object {
        val main = setting("metrics", MetricSettings())
        val toReport = ConcurrentLinkedQueue<MetricEvent>()
        val logger = LoggerFactory.getLogger(Metrics::class.java)
        init {
            Tasks.onEngineReady {
//                regularlyAndOnShutdown(Duration.ofSeconds(15)) {
                regularlyAndOnShutdown(Duration.ofMinutes(5)) {
                    logger.info("Assembling metrics to report...")
                    val assembledData = ArrayList<MetricEvent>(toReport.size)
                    while(true) {
                        val item = toReport.poll() ?: break
                        assembledData.add(item)
                    }
                    logger.info("Reporting ${assembledData.size} metric events to ${main()}...")
                    main().report(assembledData)
                    logger.info("Report complete.")
                }
            }
        }
        suspend fun report(type: String, value: Double) = toReport.offer(MetricEvent(type, serverEntryPoint()?.toString() ?: "Unknown", Instant.now(), value))
        suspend fun <T> performance(type: String, action: suspend() -> T): T {
            val start = System.nanoTime()
            val result = action()
            report(type, (System.nanoTime() - start) / 1000000.0)
            return result
        }
        suspend fun <T> handlerPerformance(handler: Any, action: suspend ()->T): T {
            return serverEntryPoint(handler) {
                performance("executionTime", action)
            }
        }
    }
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
        runBlocking {
            action()
        }
    })
}
/*

Kinds of metrics we need to track:

Instance - How many times did this occur?
Distribution - How long did X take

Stats regularly split by entry point

Track min/avg/max/sum/count per metric

DB Query Time
Calculation Time
Usages of each entry point - SUM


 */
