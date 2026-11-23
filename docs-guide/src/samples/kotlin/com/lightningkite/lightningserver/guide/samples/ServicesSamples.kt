package com.lightningkite.lightningserver.guide.samples

// region services-imports
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.engine.local.engineCache
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.cache.*
import kotlin.test.*
// endregion services-imports

// region counter-server
object CounterServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val cache = setting("cache", Cache.Settings())

    val increment = path.path("counter").post bind ApiHttpHandler(
        summary = "Increment a named counter",
        auth = noAuth,
        implementation = { name: String -> cache().add("counter:$name", 1L) }
    )
}
// endregion counter-server

// region counter-test
fun counterTest() = CounterServer.testBlocking(settings = { cache set Cache.Settings("ram") }) {
    assertEquals(1L, increment.test(null, "hits"))
    assertEquals(2L, increment.test(null, "hits"))
}
// endregion counter-test

// region counter-set-static
fun counterStaticTest() = CounterServer.testBlocking(settings = { context -> cache setStatic MapCache("test", context) }) {
    assertEquals(1L, increment.test(null, "hits"))
}
// endregion counter-set-static

// region shared-cache
object SharedCacheServer : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())

    init {
        // The engine's own "engine-cache" setting now resolves to this one and leaves settings.json.
        engineCache bind cache
    }
}
// endregion shared-cache

fun sharedCacheTest() = SharedCacheServer.testBlocking(settings = {}) {
    assertSame(cache(), engineCache())
}
