package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.websocket.WebSocketIdentifier
import kotlinx.serialization.KSerializer

open class TypedWebSocketSender<OUTPUT>(
    val socketId: WebSocketIdentifier,
    val outputSerializer: KSerializer<OUTPUT>,
    val cache: Cache,
) {
    suspend fun send(value: OUTPUT) {
        socketId.send(Serialization.json.encodeToString(outputSerializer, value))
    }
}