package com.lightningkite.lightningserver.pubsub

import com.lightningkite.lightningserver.logging.LoggingSettings
import com.lightningkite.lightningserver.logging.loggingSettings
import com.lightningkite.lightningserver.settings.Settings
import io.lettuce.core.RedisClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.take
import org.junit.Test
import redis.embedded.RedisServer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test fun testInterruption() {
        Settings.clear()
        Settings.populateDefaults(mapOf(loggingSettings.name to LoggingSettings(default = LoggingSettings.ContextSettings(
            toConsole = true, level = "INFO", filePattern = null
        ))))
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
        var gotElement = false
        GlobalScope.launch {
            val pubSub = RedisPubSub(RedisClient.create("redis://127.0.0.1:6379/0"))
            val channel = pubSub.get<Int>("Test")
            launch {
                var num = 0
                while(true) {
                    delay(100L)
                    println("Sending")
                    try {
                        channel.emit(num++)
                    } catch(e: Exception) {
                        // squish
                    }
                }
            }
            channel.collect {
                println("got $it")
                gotElement = true
            }
        }
        println("Going...")
        Thread.sleep(1000L)
        println("Stopping...")
        redisServer.stop()
        Thread.sleep(1000L)
        gotElement = false
        println("Going...")
        redisServer.start()
        Thread.sleep(1000L)
        assertTrue(gotElement)
        println("Stopping...")
        redisServer.stop()
    }
}