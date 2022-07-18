package com.lightningkite.lightningserver.cache

import com.lightningkite.lightningserver.serialization.Serialization
import io.lettuce.core.RedisClient
import io.lettuce.core.SetArgs
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.collect
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import redis.embedded.RedisServer
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class RedisCache(val client: RedisClient): CacheInterface {
    companion object {
        init {
            CacheSettings.register("redis-test") {
                val redisServer = RedisServer.builder()
                    .port(6379)
                    .setting("bind 127.0.0.1") // good for local development on Windows to prevent security popups
                    .slaveOf("localhost", 6378)
                    .setting("daemonize no")
                    .setting("appendonly no")
                    .setting("maxmemory 128M")
                    .build()
                redisServer.start()
                RedisCache(RedisClient.create("redis://127.0.0.1:6378"))
            }
            CacheSettings.register("redis") {
                RedisCache(RedisClient.create(it.uri + (it.connectionString ?: "")))
            }
        }
    }

    val connection = client.connect().reactive()
    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? {
        return connection.get(key).awaitFirstOrNull()?.let { Serialization.json.decodeFromString(serializer, it) }
    }

    override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?) {
        connection.set(key, Serialization.json.encodeToString(serializer, value), SetArgs().let { timeToLive?.toMillis()?.let { t -> it.ex(t) } ?: it }).collect {}
    }

    override suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>
    ): Boolean {
        return connection.setnx(key, Serialization.json.encodeToString(serializer, value)).awaitFirst()
    }

    override suspend fun add(key: String, value: Int) {
        connection.incrby(key, value.toLong()).collect {  }
    }

    override suspend fun clear() {
        connection.flushdb().collect {  }
    }

    override suspend fun remove(key: String) {
        connection.del(key).collect {  }
    }
}