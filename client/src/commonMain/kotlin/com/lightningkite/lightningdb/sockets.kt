
package com.lightningkite.lightningdb

// import com.lightningkite.khrysalis.*
import com.lightningkite.rock.*
import com.lightningkite.rock.reactive.*
import com.lightningkite.uuid
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun retryWebsocket(
    url: String
): RetryWebsocket {
    val connected = Property(false)
    var currentWebSocket: WebSocket? = null
    val onOpenList = ArrayList<()->Unit>()
    val onMessageList = ArrayList<(String)->Unit>()
    val onBinaryMessageList = ArrayList<(Blob)->Unit>()
    val onCloseList = ArrayList<(Short)->Unit>()
    fun reset() {
        currentWebSocket = websocket(url).also {
            onOpenList.forEach { l -> it.onOpen(l) }
            onMessageList.forEach { l -> it.onMessage(l) }
            onBinaryMessageList.forEach { l -> it.onBinaryMessage(l) }
            onCloseList.forEach { l -> it.onClose(l) }
        }
    }

    return object: RetryWebsocket {
        private val stayOpenP = ResourceUseImpl()
        override val stayOpen: ResourceUse = stayOpenP.use
        val scope = ReactiveScope {
            val shouldBeOn = stayOpenP.current
            val isOn = connected.current
            if(shouldBeOn && !isOn) {
                reset()
            } else if(!shouldBeOn && isOn) {
                currentWebSocket?.close(1000, "OK")
            }
        }

        override fun close(code: Short, reason: String) {
            scope.cancel()
            currentWebSocket?.close(code, reason)
            currentWebSocket = null
        }

        override fun send(data: Blob) {
            currentWebSocket?.send(data)
        }

        override fun send(data: String) {
            currentWebSocket?.send(data)
        }

        override fun onOpen(action: ()->Unit) { onOpenList.add(action) }
        override fun onMessage(action: (String)->Unit) { onMessageList.add(action) }
        override fun onBinaryMessage(action: (Blob)->Unit) { onBinaryMessageList.add(action) }
        override fun onClose(action: (Short)->Unit) { onCloseList.add(action) }
    }
}

interface TypedWebSocket<SEND, RECEIVE> {
    val stayOpen: ResourceUse
    fun close(code: Short, reason: String)
    fun send(data: SEND)
    fun onOpen(action: ()->Unit)
    fun onMessage(action: (RECEIVE)->Unit)
    fun onClose(action: (Short)->Unit)

}

interface RetryWebsocket: WebSocket {
    val stayOpen: ResourceUse
}

/*
wrap Pinging atLeast WebSocket {
    override fun onMessage(action: (String)->Unit) { onMessageList.add(action) }
}

 */

class ResourceUseImpl(private val p: Property<Boolean> = Property(false)): Readable<Boolean> by p, OnRemoveHandler {
    var count = 0
    val use: ResourceUse = object: ResourceUse {
        override fun start(): () -> Unit {
            if(count++ == 0) p set true
            return {
                if(--count == 0) p set false
            }
        }
    }

}


val <RECEIVE> TypedWebSocket<*, RECEIVE>.mostRecentMessage: Readable<RECEIVE?> get() = object: Readable<RECEIVE?> {
    override var once: RECEIVE? = null
        private set

    val listeners = HashSet<()->Unit>()
    init {
        onMessage {
            once = it
            listeners.forEach { it() }
        }
    }

    override fun addListener(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }
}


fun <SEND, RECEIVE> RetryWebsocket.typed(json: Json, send: KSerializer<SEND>, receive: KSerializer<RECEIVE>): TypedWebSocket<SEND, RECEIVE> = object: TypedWebSocket<SEND, RECEIVE> {
    override val stayOpen: ResourceUse get() = this@typed.stayOpen
    override fun close(code: Short, reason: String) = this@typed.close(code, reason)
    override fun onOpen(action: () -> Unit) = this@typed.onOpen(action)
    override fun onClose(action: (Short) -> Unit) = this@typed.onClose(action)
    override fun onMessage(action: (RECEIVE) -> Unit) {
        this@typed.onMessage {
            try {
                action(json.decodeFromString(receive, it))
            } catch(e: Exception) {
                TODO("Figure out error handling")
            }
        }
    }
    override fun send(data: SEND) {
        this@typed.send(json.encodeToString(send, data))
    }
}

private val shared = HashMap<String, TypedWebSocket<MultiplexMessage, MultiplexMessage>>()
fun multiplexSocket(url: String, path: String, params: Map<String, List<String>>, json: Json): RetryWebsocket {
    val shared = shared.getOrPut(url) {
        val s = retryWebsocket(url)
        // TODO: Pings
        s.typed(json, MultiplexMessage.serializer(), MultiplexMessage.serializer())
    }
    var channelOpen = false
    val channel = uuid().toString()
    return object: RetryWebsocket {
        private val stayOpenP = ResourceUseImpl()
        override val stayOpen: ResourceUse = stayOpenP.use
        val scope = ReactiveScope {
            val shouldBeOn = stayOpenP.current
            val isOn = channelOpen
            if(shouldBeOn && !isOn) {
                shared.send(MultiplexMessage(
                    channel = channel,
                    path = path,
                    queryParams = params,
                    start = true
                ))
            } else if(!shouldBeOn && isOn) {
                shared.send(MultiplexMessage(
                    channel = channel,
                    path = path,
                    queryParams = params,
                    end = true
                ))
            }
        }

        override fun close(code: Short, reason: String) {
            shared.send(MultiplexMessage(
                channel = channel,
                path = path,
                queryParams = params,
                end = true
            ))
            scope.cancel()
        }

        override fun send(data: Blob) = throw UnsupportedOperationException()

        override fun send(data: String) {
            shared.send(
                MultiplexMessage(
                    channel = channel,
                    data = data,
                )
            )
        }

        val onOpenList = ArrayList<()->Unit>()
        val onMessageList = ArrayList<(String)->Unit>()
        val onCloseList = ArrayList<(Short)->Unit>()
        override fun onOpen(action: ()->Unit) { onOpenList.add(action) }
        override fun onMessage(action: (String)->Unit) { onMessageList.add(action) }
        override fun onBinaryMessage(action: (Blob)->Unit) = throw UnsupportedOperationException()
        override fun onClose(action: (Short)->Unit) { onCloseList.add(action) }

        init {
            shared.onOpen {
                channelOpen = false
            }
            shared.onMessage { message ->
                if(message.channel == channel) {
                    if(message.start) onOpenList.forEach { it() }
                    message.data?.let { data ->
                        onMessageList.forEach { it(data) }
                    }
                    if(message.end) onCloseList.forEach { it(-1) }
                }
            }
            shared.onClose {
                channelOpen = false
            }
        }
    }
}

private fun sample(): SharedReadable<List<Int>> {
    val ws = multiplexSocket("testurl", "rest/model", mapOf(), Json).typed(Json, Int.serializer(), Int.serializer())
    ws.onOpen { ws.send(5) }
    val fullList = ArrayList<Int>()
    return shared {
        blockIfBackground()
        use(ws.stayOpen)
        ws.mostRecentMessage.current?.let {
            fullList.add(it)
        }
        fullList
    }
}
