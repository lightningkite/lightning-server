package com.lightningkite.lightningserver.cache

import kotlinx.coroutines.*
import net.rubyeye.xmemcached.XMemcachedClient
import org.junit.Assert.assertEquals
import org.junit.Test

class MemcachedTest {
    @Test
    fun test() {
        if(!EmbeddedMemcached.available) {
            println("Cannot test memcached; not available on system")
            return
        }
        val redisServer = EmbeddedMemcached.start()
        try {
            val pubSubA = MemcachedCache(XMemcachedClient("127.0.0.1", 11211))
            val pubSubB = MemcachedCache(XMemcachedClient("127.0.0.1", 11211))
            runBlocking {
                pubSubA.set("test", 4)
                assertEquals(4, pubSubB.get<Int>("test"))
            }
        } finally {
            redisServer.destroy()
        }
    }
}