package com.lightningkite.lightningserver.cache

import kotlinx.coroutines.runBlocking
import net.rubyeye.xmemcached.XMemcachedClient
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

class MemcachedTest: CacheTest() {
    override val cache: Cache? by lazy {
        if (EmbeddedMemcached.available) MemcachedCache(XMemcachedClient("127.0.0.1", 11211))
        else null
    }

    companion object {
        var memcached: Process? = null
        @JvmStatic
        @BeforeClass
        fun start() {
            if(!EmbeddedMemcached.available) {
                println("Cannot test memcached; not available on system")
                return
            }
            memcached = EmbeddedMemcached.start()
        }
        @JvmStatic
        @AfterClass
        fun stop() {
            memcached?.destroy()
        }
    }

    @Test
    fun testMultiClient() {
        if(!EmbeddedMemcached.available) {
            println("Cannot test memcached; not available on system")
            return
        }
        try {
            val pubSubA = MemcachedCache(XMemcachedClient("127.0.0.1", 11211))
            val pubSubB = MemcachedCache(XMemcachedClient("127.0.0.1", 11211))
            runBlocking {
                pubSubA.set("test", 4)
                assertEquals(4, pubSubB.get<Int>("test"))
            }
        } finally {
        }
    }
}
