package com.lightningkite.ktorbatteries.pubsub

import com.lightningkite.ktorbatteries.serialization.Serialization
import kotlinx.coroutines.flow.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

interface PubSubInterface {
    fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T>
}
inline operator fun <reified T: Any> PubSubInterface.get(key: String): PubSubChannel<T> {
    return get(key, Serialization.json.serializersModule.serializer<T>())
}

interface PubSubChannel<T>: Flow<T>, FlowCollector<T> {}

private suspend fun usageTest(i: PubSubInterface) {
    val channel: PubSubChannel<Int> = i["asdf"]
    channel.emit(232)
    channel.map { it + 1 }.collect { println(it) }
}