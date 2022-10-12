package com.lightningkite.lightningserver.engine

import com.lightningkite.lightningserver.cache.CacheInterface
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.set
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.pubsub.PubSubInterface
import com.lightningkite.lightningserver.pubsub.get
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.tasks.Task
import com.lightningkite.lightningserver.tasks.Tasks
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import org.slf4j.LoggerFactory
import java.time.Duration

interface Engine {
    suspend fun sendWebSocketMessage(id: String, content: String): Boolean
    suspend fun listenForWebSocketMessage(id: String): Flow<String> = throw UnsupportedOperationException()
    suspend fun launchTask(task: Task<Any?>, input: Any?)
}

class LocalEngine(val pubSub: PubSubInterface, val cache: CacheInterface): Engine {
    val logger = LoggerFactory.getLogger(this::class.java)
    suspend fun webSocketConnected(id: String) {
        cache.set("ws-$id-connected", true, timeToLive = Duration.ofDays(1))
    }

    override suspend fun sendWebSocketMessage(id: String, content: String): Boolean {
        pubSub.string("ws-$id").emit(content)
        return cache.get<Boolean>("ws-$id-connected") ?: false
    }

    override suspend fun listenForWebSocketMessage(id: String): Flow<String> {
        return pubSub.string("ws-$id")
            .onEach {
                if(generalSettings().debug) {
                    logger.trace("Sending $it to $id")
                }
            }
            .onStart {
                cache.set("ws-$id-connected", true, timeToLive = Duration.ofDays(1))
                logger.debug("Ready for outgoing messages to $id")
            }
            .onCompletion {
                cache.set("ws-$id-connected", false)
                logger.debug("Done watching for outgoing messages $id")
            }
    }

    override suspend fun launchTask(task: Task<Any?>, input: Any?) {
        GlobalScope.launch {
            Metrics.handlerPerformance(task) {
                task.implementation(this, input)
            }
        }
    }
}

lateinit var engine: Engine