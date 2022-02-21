package com.lightningkite.ktorbatteries.pubsub

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.KSerializer

object LocalPubSub: PubSubInterface {
    val known = HashMap<String, PubSubChannel<*>>()
    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T> = known.getOrPut(key) {
        val s = MutableSharedFlow<T>(0)
        object: PubSubChannel<T>, Flow<T> by s, FlowCollector<T> by s {}
    } as PubSubChannel<T>
}