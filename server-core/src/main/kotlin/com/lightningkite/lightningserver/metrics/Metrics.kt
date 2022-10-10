package com.lightningkite.lightningserver.metrics

import com.lightningkite.lightningserver.core.serverEntryPoint
import com.lightningkite.lightningserver.settings.setting
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.time.delay
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min

interface Metrics {
    suspend fun report(events: List<MetricEvent>)
    companion object {
        val main = setting("metrics", MetricSettings())
        val toReport = ConcurrentLinkedQueue<MetricEvent>()
        init {
            regularlyAndOnShutdown(Duration.ofSeconds(15)) {
//            regularlyAndOnShutdown(Duration.ofMinutes(15)) {
                val assembledData = ArrayList<MetricEvent>(toReport.size)
                while(true) {
                    val item = toReport.poll() ?: break
                    assembledData.add(item)
                }
                main().report(assembledData)
            }
        }
        suspend fun report(type: String, value: Double) = toReport.offer(MetricEvent(type, serverEntryPoint()?.toString() ?: "Unknown", Instant.now(), value))
        suspend inline fun <T> performance(type: String, action: () -> T): T {
            val start = System.nanoTime()
            val result = action()
            report(type, (System.nanoTime() - start) / 1000000.0)
            return result
        }
        suspend inline fun <T> handlerPerformance(handler: Any, crossinline action: suspend ()->T): T {
            return serverEntryPoint(handler) {
                performance("executionTime") {
                    action()
                }
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
