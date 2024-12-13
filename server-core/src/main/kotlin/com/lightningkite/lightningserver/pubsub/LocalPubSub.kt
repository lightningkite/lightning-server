package com.lightningkite.lightningserver.pubsub

import com.lightningkite.lightningserver.core.serverLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import java.util.concurrent.ConcurrentHashMap

object BadPubSub : PubSub {
    val known = HashMap<String, PubSubChannel<*>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T> = known.getOrPut(key) {
        val s = MutableSharedFlow<T>(0)
        object : PubSubChannel<T>, Flow<T> by s, FlowCollector<T> by s {}
    } as PubSubChannel<T>

    override fun string(key: String): PubSubChannel<String> = get(key, String.serializer())
}

object LocalPubSub : PubSub {
    val known = ConcurrentHashMap<String, PubSubChannel<*>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T> = known.getOrPut(key) {
        val s = MutableSharedFlow<T>(0)
        object : PubSubChannel<T>, Flow<T> by s, FlowCollector<T> by s {}
    } as PubSubChannel<T>

    override fun string(key: String): PubSubChannel<String> = get(key, String.serializer())
}

object DebugPubSub : PubSub {
    init { serverLogger.info("Using debug pub sub") }
    val known = ConcurrentHashMap<String, PubSubChannel<*>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T> = known.getOrPut(key) {
        serverLogger.info("Created channel $key")
        val s = MutableSharedFlow<T>(0)
        object : PubSubChannel<T>, Flow<T> by s, FlowCollector<T> by s {
            override suspend fun emit(value: T) {
                serverLogger.info("DebugPubSub: emit ${key} to ${s.subscriptionCount.value}")
                s.emit(value)
            }
        }
    } as PubSubChannel<T>

    override fun string(key: String): PubSubChannel<String> = get(key, String.serializer())
}