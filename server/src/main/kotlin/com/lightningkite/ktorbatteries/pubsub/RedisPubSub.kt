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
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class RedisPubSub(val client: RedisClient): PubSubInterface {
    val observables = ConcurrentHashMap<String, Flux<String>>()
    val subscribeConnection = client.connectPubSub().reactive()
    val publishConnection = client.connectPubSub().reactive()
    private fun key(key: String) = observables.getOrPut(key) {
        val reactive = subscribeConnection
        Flux.usingWhen(
            reactive.subscribe(key).then(Mono.just(reactive)).doOnSuccess { println("Subscribed") },
            {
                it.observeChannels()
                    .doOnNext { println("Got $it") }
                    .filter { it.channel == key }
            },
            { it.unsubscribe(key).doOnSuccess { println("Unsubscribed") } }
        ).map { it.message }
            .doOnError { it.printStackTrace() }
            .doOnComplete { println("Completed") }
            .share()
    }
    override fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T> {
        return object: PubSubChannel<T> {
            override suspend fun collect(collector: FlowCollector<T>) {
                key(key).map { Serialization.json.decodeFromString(serializer, it) }.collect { collector.emit(it) }
            }

            override suspend fun emit(value: T) {
                publishConnection.publish(key, Serialization.json.encodeToString(serializer, value)).awaitFirst()
            }
        }
    }
}