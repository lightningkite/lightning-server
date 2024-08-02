package com.lightningkite.lightningserver.cache

import com.lightningkite.lightningserver.serialization.Serialization
import io.lettuce.core.GetExArgs
import io.lettuce.core.RedisClient
import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisListCommands
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.collect
import kotlinx.serialization.KSerializer
import redis.embedded.RedisExecProvider
import redis.embedded.RedisServer
import redis.embedded.util.Architecture
import redis.embedded.util.OS
import redis.embedded.util.OsArchitecture
import kotlin.time.Duration
import kotlin.time.toJavaDuration

class RedisCache(val client: RedisClient) : Cache {
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
                RedisCache(RedisClient.create(it.url + (it.connectionString ?: "")))
            }
        }
    }

    val connection = client.connect().reactive()
    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? {
        return connection.get(key).awaitFirstOrNull()?.let { Serialization.Internal.json.decodeFromString(serializer, it) }
    }

    override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?) {
        connection.set(
            key,
            Serialization.Internal.json.encodeToString(serializer, value),
            SetArgs().let { timeToLive?.inWholeMilliseconds?.let { t -> it.px(t) } ?: it }
        ).collect {}
    }

    override suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?
    ): Boolean {
        val result = connection.setnx(key, Serialization.Internal.json.encodeToString(serializer, value)).awaitFirst()
        if(result) timeToLive?.let { connection.pexpire(key, it.toJavaDuration()).collect {  } }
        return result
    }

//    val cas = connection.scriptLoad(
//        """
//            if redis.call(""get"", KEYS[1]) == ARGV[1] then
//                redis.call(""set"", KEYS[1], ARGV[2])
//                return 1
//            else
//                return 0
//            end
//        """.trimIndent()
//    ).share()
//
//    init {
//        cas.subscribe()
//    }
//
//    override suspend fun <T> modify(key: String, serializer: KSerializer<T>, maxTries: Int, modification: (T?) -> T?): Boolean {
//        val scriptName = cas.awaitSingle()
//        repeat(maxTries) {
//            val current = get(key, serializer)
//            val new = modification(current)
//            if(connection.eval<Boolean>(
//                scriptName,
//                ScriptOutputType.BOOLEAN,
//                current?.let { Serialization.json.encodeToString(serializer, it) },
//                new?.let { Serialization.json.encodeToString(serializer, it) }
//            ).awaitSingle()) return true
//        }
//        return false
//    }

    override suspend fun add(key: String, value: Int, timeToLive: Duration?) {
        connection.incrby(key, value.toLong()).collect { }
        timeToLive?.let {
            connection.pexpire(key, it.toJavaDuration()).collect {  }
        }
    }

    override suspend fun remove(key: String) {
        connection.del(key).collect { }
    }
}