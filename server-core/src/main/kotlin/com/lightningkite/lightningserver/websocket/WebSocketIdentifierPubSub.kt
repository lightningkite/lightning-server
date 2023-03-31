package com.lightningkite.lightningserver.websocket

import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.set
import com.lightningkite.lightningserver.pubsub.PubSub
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile
import java.util.*

class WebSocketIdentifierPubSub(val type: String = "ws-pubsub", val pubSub: PubSub, val cache: Cache) {
    val closeMessage = "___close____"

    init {
        WebSocketIdentifier.register(
            type = type,
            send = { id, value ->
                val sid = WebSocketIdentifier(type, id)
                pubSub.string(sid.toString()).emit(value)
                cache.get<Boolean>("$sid-connected") ?: false
            },
            close = { id ->
                val sid = WebSocketIdentifier(type, id)
                val exists = cache.get<Boolean>("$sid-connected") ?: false
                cache.remove("$sid-connected")
                pubSub.string(sid.toString()).emit(closeMessage)
                exists
            }
        )
    }

    suspend fun connect(): WebSocketIdentifier {
        val uuid = UUID.randomUUID().toString()
        val sid = WebSocketIdentifier(type, uuid)
        cache.set<Boolean>("$sid-connected", true)
        return sid
    }

    suspend fun markDisconnect(sid: WebSocketIdentifier) {
        cache.remove("${sid}-connected")
    }

    fun listenForWebSocketMessage(sid: WebSocketIdentifier): Flow<String> {
        return pubSub.string(sid.toString())
            .takeWhile { it != closeMessage }
    }
}