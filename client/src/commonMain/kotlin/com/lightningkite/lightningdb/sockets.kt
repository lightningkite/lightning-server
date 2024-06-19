package com.lightningkite.lightningdb

import com.lightningkite.kiteui.*
import com.lightningkite.kiteui.reactive.*
import com.lightningkite.uuid
import kotlinx.serialization.json.Json

private val shared = HashMap<String, TypedWebSocket<MultiplexMessage, MultiplexMessage>>()
fun multiplexSocket(url: String, path: String, params: Map<String, List<String>>, json: Json, pingTime: Long = 30_000L): RetryWebsocket {
    val shared = shared.getOrPut(url) {
        val s = retryWebsocket(url, pingTime)
        s.typed(json, MultiplexMessage.serializer(), MultiplexMessage.serializer())
    }
    val channelOpen = Property(false)
    val channel = uuid().toString()
    return object : RetryWebsocket {
        init {
            shared.onMessage { message ->
                if (message.channel == channel) {
                    if (message.start) {
                        channelOpen.value = true
                        onOpenList.forEach { it() }
                    }
                    message.data?.let { data ->
                        onMessageList.forEach { it(data) }
                    }
                    if (message.end) {
                        channelOpen.value = false
                        onCloseList.forEach { it(-1) }
                    }
                }
            }
            shared.onClose {
                channelOpen.value = false
            }
        }

        override val connected: Readable<Boolean>
            get() = channelOpen
        val shouldBeOn = Property(0)

        override fun start(): () -> Unit {
            shouldBeOn.value++
            val parent = shared.start()
            return {
                parent()
                shouldBeOn.value--
            }
        }
        val lifecycle = CalculationContext.Standard().apply {
            reactiveScope {
                val shouldBeOn = shouldBeOn.await() > 0
                val isOn = channelOpen.await()
                val parentConnected = shared.connected.await()
                if (shouldBeOn && parentConnected && !isOn) {
                    shared.send(
                        MultiplexMessage(
                            channel = channel,
                            path = path,
                            queryParams = params,
                            start = true
                        )
                    )
                } else if (!shouldBeOn && parentConnected && isOn) {
                    shared.send(
                        MultiplexMessage(
                            channel = channel,
                            path = path,
                            queryParams = params,
                            end = true
                        )
                    )
                }
            }
        }

        override fun close(code: Short, reason: String) {
            shared.send(
                MultiplexMessage(
                    channel = channel,
                    path = path,
                    queryParams = params,
                    end = true
                )
            )
            lifecycle.cancel()
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

        val onOpenList = ArrayList<() -> Unit>()
        val onMessageList = ArrayList<(String) -> Unit>()
        val onCloseList = ArrayList<(Short) -> Unit>()
        override fun onOpen(action: () -> Unit) {
            onOpenList.add(action)
        }

        override fun onMessage(action: (String) -> Unit) {
            onMessageList.add(action)
        }

        override fun onBinaryMessage(action: (Blob) -> Unit) = throw UnsupportedOperationException()
        override fun onClose(action: (Short) -> Unit) {
            onCloseList.add(action)
        }
    }
}

