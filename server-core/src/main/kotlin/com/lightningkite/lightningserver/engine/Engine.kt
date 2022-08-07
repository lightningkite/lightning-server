package com.lightningkite.lightningserver.engine

import com.lightningkite.lightningserver.pubsub.PubSubInterface
import com.lightningkite.lightningserver.pubsub.get
import com.lightningkite.lightningserver.tasks.Task
import com.lightningkite.lightningserver.tasks.Tasks
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

interface Engine {
    suspend fun sendWebSocketMessage(id: String, content: String)
    suspend fun listenForWebSocketMessage(id: String): Flow<String> = throw UnsupportedOperationException()
    @Suppress("OPT_IN_USAGE")
    suspend fun launchTask(task: Task<Any?>, input: Any?)
}

class LocalEngine(val pubSub: PubSubInterface): Engine {
    val logger = LoggerFactory.getLogger(this::class.java)
    override suspend fun sendWebSocketMessage(id: String, content: String) {
        logger.debug("Sending $content to $id")
        pubSub.string("ws-$id").emit(content)
    }

    override suspend fun listenForWebSocketMessage(id: String): Flow<String> {
        logger.debug("Listener for $id made")
        return pubSub.string("ws-$id").map { println("Send intercept $id $it"); it }
    }

    override suspend fun launchTask(task: Task<Any?>, input: Any?) {
        logger.debug("Launching ${task.name} with $input")
        GlobalScope.launch {
            logger.debug("Executing ${task.name} with $input")
            task.implementation(this, input)
        }
    }
}

lateinit var engine: Engine