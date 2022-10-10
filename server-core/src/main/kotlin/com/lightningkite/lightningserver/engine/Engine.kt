package com.lightningkite.lightningserver.engine

import com.lightningkite.lightningserver.cache.CacheInterface
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.set
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.pubsub.PubSubInterface
import com.lightningkite.lightningserver.pubsub.get
import com.lightningkite.lightningserver.tasks.Task
import com.lightningkite.lightningserver.tasks.Tasks
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration

interface Engine {
    suspend fun sendWebSocketMessage(id: String, content: String): Boolean
    suspend fun listenForWebSocketMessage(id: String): Flow<String> = throw UnsupportedOperationException()
    suspend fun launchTask(task: Task<Any?>, input: Any?)
}

class LocalEngine(val pubSub: PubSubInterface, val cache: CacheInterface): Engine {
    val logger = LoggerFactory.getLogger(this::class.java)
    override suspend fun sendWebSocketMessage(id: String, content: String): Boolean {
        logger.debug("Sending $content to $id")
        pubSub.string("ws-$id").emit(content)
        return cache.get<Boolean>("ws-$id-connected") ?: false
    }

    override suspend fun listenForWebSocketMessage(id: String): Flow<String> {
        logger.debug("Listener for $id made")
        return pubSub.string("ws-$id").map { println("Send intercept $id $it"); it }
            .onStart { cache.set("ws-$id-connected", true, timeToLive = Duration.ofDays(1)) }
            .onCompletion { cache.set("ws-$id-connected", false) }
    }

    override suspend fun launchTask(task: Task<Any?>, input: Any?) {
        logger.debug("Launching ${task.name} with $input")
        GlobalScope.launch {
            logger.debug("Executing ${task.name} with $input")
            Metrics.handlerPerformance(task) {
                task.implementation(this, input)
            }
        }
    }
}

lateinit var engine: Engine