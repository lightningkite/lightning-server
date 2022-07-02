package com.lightningkite.lightningserver.pubsub

import io.lettuce.core.RedisClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.take
import org.junit.Test
import redis.embedded.RedisServer
import kotlin.test.assertEquals

class RedisTest {
    @Test
    fun test() {
        val redisServer = RedisServer.builder()
            .port(6379)
            .setting("bind 127.0.0.1") // good for local development on Windows to prevent security popups
            .slaveOf("localhost", 6379)
            .setting("daemonize no")
            .setting("appendonly no")
            .setting("replica-read-only no")
            .setting("maxmemory 128M")
            .build()
        redisServer.start()
        try {
            val pubSub = RedisPubSub(RedisClient.create("redis://127.0.0.1:6379/0"))
            val channel = pubSub.get<Int>("Test")
            var received: Int? = null
            runBlocking {
                val sendJob = launch {
                    repeat(10) {
                        delay(100L)
                        channel.emit(22)
                    }
                }
                channel.take(3).collectLatest { received = it }
                sendJob.cancelAndJoin()
            }
            assertEquals(22, received)
        } finally {
            redisServer.stop()
        }
    }
}