package com.lightningkite.ktorbatteries.pubsub

import com.lightningkite.ktorbatteries.serialization.Serialization
import io.lettuce.core.RedisClient
import io.lettuce.core.pubsub.RedisPubSubListener
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.collect
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString

class RedisPubSub(val client: StatefulRedisPubSubConnection<String, String>): PubSubInterface {
    override fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T> {
        val listen = client.reactive().observeChannels()
            .filter { it.channel == key }
            .map { Serialization.json.decodeFromString(serializer, it.message) }
        return object: PubSubChannel<T> {
            @InternalCoroutinesApi
            override suspend fun collect(collector: FlowCollector<T>) {
                listen.collect { collector.emit(it) }
            }

            override suspend fun emit(value: T) {
                client.reactive().publish(key, Serialization.json.encodeToString(serializer, value)).awaitFirst()
            }
        }
    }
}