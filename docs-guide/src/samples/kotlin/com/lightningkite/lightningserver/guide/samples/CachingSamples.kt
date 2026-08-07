package com.lightningkite.lightningserver.guide.samples

// region caching-imports
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.services.cache.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
// endregion caching-imports

// region cache-server
object CacheServer : ServerBuilder() {
    // "cache" becomes the key in settings.json.
    // Cache.Settings() defaults to "ram" — a ConcurrentHashMap-backed in-process cache.
    val cache = setting("cache", Cache.Settings())
}
// endregion cache-server

// region cache-test
fun cacheTest() = CacheServer.testBlocking(settings = { cache set Cache.Settings("ram") }) {
    val c = CacheServer.cache()

    // set + get round-trip
    c.set("greeting", "hello")
    assertEquals("hello", c.get<String>("greeting"))

    // null on a miss
    assertNull(c.get<String>("no-such-key"))

    // overwrite: set replaces the existing value
    c.set("greeting", "world")
    assertEquals("world", c.get<String>("greeting"))

    // remove
    c.remove("greeting")
    assertNull(c.get<String>("greeting"))

    // getAndRemove: returns the value and atomically deletes the key in one step
    c.set("verify:token-abc", "user@example.com", timeToLive = 30.minutes)
    assertEquals("user@example.com", c.getAndRemove<String>("verify:token-abc"))
    assertNull(c.getAndRemove<String>("verify:token-abc"))  // key is now gone

    // setIfNotExists: write only if the key is absent — the basis for distributed locks
    assertTrue(c.setIfNotExists("lock:report", "locked", timeToLive = 5.minutes))
    assertFalse(c.setIfNotExists("lock:report", "locked", timeToLive = 5.minutes))  // already exists

    // add: atomic increment; key is created when it first appears
    assertEquals(1L, c.add("hits:page1", 1L))
    assertEquals(2L, c.add("hits:page1", 1L))
    assertEquals(1L, c.add("hits:page1", -1L))  // decrement
}
// endregion cache-test
