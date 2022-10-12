package com.lightningkite.lightningserver.pubsub

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class LocalPubSubTest {
    @Test
    fun test() {
        runBlocking {
            val x = LocalPubSub.get<String>("asdf")
            launch {
                withTimeout(200L) {
                    println("Starting a listen")
                    x.take(1).collect {
                        println("Got $it")
                    }
                }
            }
            delay(100L)
            println("Emitting")
            x.emit("Test")
        }
    }
}