package com.lightningkite.lightningserver.websocket

import com.lightningkite.lightningserver.engine.engine
import kotlinx.serialization.Serializable
import kotlin.collections.set

@JvmInline
@Serializable(WebSocketIdentifierSerializer::class)
value class WebSocketIdentifier(val string: String) : Comparable<WebSocketIdentifier> {
    constructor(type: String, id: String) : this("$type|$id")

    val type: String get() = string.substringBefore('|')
    val id: String get() = string.substringAfter('|')
    suspend fun send(content: String): Boolean = WebSockets.interceptSend(this, content) { id, content -> Companion.send(id.type, id.id, content) }
    suspend fun close(): Boolean = WebSockets.interceptClose(this) { id -> Companion.close(id.type, id.id) }

    companion object {
        fun register(
            type: String,
            send: suspend (id: String, value: String) -> Boolean,
            close: suspend (id: String) -> Boolean
        ) {
            senders[type] = send
            closers[type] = close
        }

        fun unregister(type: String) {
            senders.remove(type)
            closers.remove(type)
        }

        private val senders = HashMap<String, suspend (id: String, value: String) -> Boolean>()
        internal suspend fun send(type: String, id: String, value: String): Boolean {
            return senders[type]?.invoke(id, value) ?: false
        }

        private val closers = HashMap<String, suspend (id: String) -> Boolean>()
        internal suspend fun close(type: String, id: String): Boolean {
            return closers[type]?.invoke(id) ?: false
        }
    }

    override fun toString(): String = string
    override fun compareTo(other: WebSocketIdentifier): Int = this.string.compareTo(other.string)
}

