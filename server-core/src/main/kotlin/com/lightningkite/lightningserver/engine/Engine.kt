package com.lightningkite.lightningserver.engine

import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.exceptions.report
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.tasks.Task
import com.lightningkite.lightningserver.websocket.WebSocketIdentifier
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.time.delay
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * An abstraction layer meant to make async tasks in each environment configurable.
 * Each implementation will use the underlying environment for launching an async task.
 */
interface Engine {
    suspend fun launchTask(task: Task<Any?>, input: Any?)
    fun backgroundReportingAction(action: suspend ()->Unit) {
        GlobalScope.launch {
            while (true) {
                delay(Duration.ofMinutes(5))
                try {
                    action()
                } catch(e: Exception) {
                    e.report()
                }
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread {
            Metrics.logger.info("Shutdown hook running...")
            runBlocking {
                action()
            }
        })
    }
}

/**
 * An Engine implementation that launches a new CoroutineScope and runs the task inside that new scope.
 * This will run asynchronously with no regard for whether the task finishes or fails. This is useful
 * during local development, as well deployment in non-serverless environments when you can.
 */
class LocalEngine(val websocketCache: Cache) : Engine {
    val logger = LoggerFactory.getLogger(this::class.java)
    override suspend fun launchTask(task: Task<Any?>, input: Any?) {
        GlobalScope.launch {
            Metrics.handlerPerformance(task) {
                task.implementation(this, input)
            }
        }
    }
}

/**
 * An Engine implementation that runs each task immediately and synchronously.
 * It guarantees that the task will have finished or failed by the time this function returns.
 * This is useful when you do not want tasks to run asynchronously such as Unit Tests,
 * hence the name UnitTestEngine.
 */
object UnitTestEngine : Engine {
    val logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun launchTask(task: Task<Any?>, input: Any?) {
        coroutineScope {
            Metrics.handlerPerformance(task) {
                task.implementation(this, input)
            }
        }
    }
}

lateinit var engine: Engine