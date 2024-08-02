package com.lightningkite.lightningserver.cache

import io.lettuce.core.RedisClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.take
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import redis.embedded.RedisExecProvider
import redis.embedded.RedisServer
import redis.embedded.util.Architecture
import redis.embedded.util.OS

class RedisTest: CacheTest() {
    override val cache: Cache? by lazy {
        RedisCache(RedisClient.create("redis://127.0.0.1:6379/0"))
    }

    companion object {
        lateinit var redisServer: RedisServer
        @JvmStatic
        @BeforeClass
        fun start() {
            redisServer = RedisServer.builder()
                .port(6379)
                .setting("bind 127.0.0.1") // good for local development on Windows to prevent security popups
                .slaveOf("localhost", 6379)
                .setting("daemonize no")
                .setting("appendonly no")
                .setting("replica-read-only no")
                .setting("maxmemory 128M")
                .build()
            redisServer.start()
        }
        @JvmStatic
        @AfterClass
        fun stop() {
            redisServer.stop()
        }
    }

}