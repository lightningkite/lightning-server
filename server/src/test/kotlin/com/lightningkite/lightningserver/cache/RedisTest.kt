package com.lightningkite.lightningserver.cache

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
            val pubSub = RedisCache(RedisClient.create("redis://127.0.0.1:6379/0"))
            runBlocking {
                pubSub.set("test", 4)
                assertEquals(4, pubSub.get<Int>("test"))
            }
        } finally {
            redisServer.stop()
        }
    }
}