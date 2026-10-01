package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.Database
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MetaEndpointsTest {
    object TestServer : ServerBuilder() {
        init {
            registerBasicMediaTypeCoders()
        }

        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())

        val meta = path.path("meta") include MetaEndpoints(
            packageName = "com.lightningkite.lightningserver.typed",
            database = database,
            cache = cache,
        )
    }

    @Test
    fun wsTesterDoesNotReflectPathParameter() {
        val payload = "'><script>alert(1)</script>"
        TestServer.test(settings = {}) {
            runBlocking {
                val response = TestServer.meta.wsTester.test(
                    queryParameters = QueryParameters(listOf("path" to payload))
                )
                val html = response.body!!.text()
                assertFalse(payload in html, html)
                assertEquals("frame-ancestors 'none'", response.headers["Content-Security-Policy"]?.root)
            }
        }
    }
}
