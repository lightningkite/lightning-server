package com.lightningkite.lightningserver.engine

import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.tasks.Task
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

interface Engine {
    suspend fun launchTask(task: Task<Any?>, input: Any?)
}

object LocalEngine : Engine {
    val logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun launchTask(task: Task<Any?>, input: Any?) {
        GlobalScope.launch {
            Metrics.handlerPerformance(task) {
                task.implementation(this, input)
            }
        }
    }
}

// The purpose of this engine is to be used in Unit tests, and the difference is all tasks are run inline,
// and not launched on a new scope.
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