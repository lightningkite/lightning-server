package com.lightningkite.lightningserver.pubsub

import com.lightningkite.lightningserver.serialization.Serialization
import io.lettuce.core.RedisClient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.collect
import kotlinx.serialization.KSerializer
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import redis.embedded.RedisServer
import java.util.concurrent.ConcurrentHashMap

class RedisPubSub(val client: RedisClient): PubSub {
    companion object {
        init {
            PubSubSettings.register("redis-test") {
                val redisServer = RedisServer.builder()
                    .port(6379)
                    .setting("bind 127.0.0.1") // good for local development on Windows to prevent security popups
                    .slaveOf("localhost", 6378)
                    .setting("daemonize no")
                    .setting("appendonly no")
                    .setting("maxmemory 128M")
                    .build()
                redisServer.start()
                RedisPubSub(RedisClient.create("redis://127.0.0.1:6378"))
            }
            PubSubSettings.register("redis") {
                RedisPubSub(RedisClient.create(it.url))
            }
        }
    }
    val observables = ConcurrentHashMap<String, Flux<String>>()
    val subscribeConnection = client.connectPubSub().reactive()
    val publishConnection = client.connectPubSub().reactive()
    private fun key(key: String) = observables.getOrPut(key) {
        val reactive = subscribeConnection
        Flux.usingWhen(
            reactive.subscribe(key).then(Mono.just(reactive)),
            {
                it.observeChannels()
                    .filter { it.channel == key }
            },
            { it.unsubscribe(key) }
        ).map { it.message }
            .doOnError { it.printStackTrace() }
            .share()
    }
    override fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T> {
        return object: PubSubChannel<T> {
            override suspend fun collect(collector: FlowCollector<T>) {
                key(key).map { Serialization.Internal.json.decodeFromString(serializer, it) }.collect { collector.emit(it) }
            }

            override suspend fun emit(value: T) {
                publishConnection.publish(key, Serialization.Internal.json.encodeToString(serializer, value)).awaitFirst()
            }
        }
    }

    override fun string(key: String): PubSubChannel<String> {
        return object: PubSubChannel<String> {
            override suspend fun collect(collector: FlowCollector<String>) {
                key(key).collect { collector.emit(it) }
            }

            override suspend fun emit(value: String) {
                publishConnection.publish(key, value).awaitFirst()
            }
        }
    }
}