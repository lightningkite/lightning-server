package com.lightningkite.lightningserver.meta

import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.test
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetaEndpointsTest {
    private val meta by lazy {
        TestSettings
        MetaEndpoints(ServerPath("meta-test"))
    }

    @Test
    fun wsTesterDoesNotReflectPathIntoHtml(): Unit = runBlocking {
        val payload = "'/><script>alert(1)</script>"
        val response = meta.wsTester.test(queryParameters = listOf("path" to payload))
        val body = response.body!!.text()
        assertFalse("<script>alert(1)</script>" in body, body)
        assertEquals("frame-ancestors 'none'", response.headers["Content-Security-Policy"])
    }

    // A path like "@evil.example" turns "wss://host" + path into a URL whose host is evil.example, and the token goes with it.
    @Test
    fun wsTesterOnlyConnectsToPathsOnThisServer(): Unit = runBlocking {
        val body = meta.wsTester.test().body!!.text()
        assertTrue("startsWith(\"/\")" in body, body)
    }

    @Test
    fun swaggerLoadsSpecByUrl(): Unit = runBlocking {
        val body = meta.openApi.test(headers = HttpHeaders("Accept" to "text/html")).body!!.text()
        assertTrue("url: 'openapi.json'" in body, body)
        assertFalse("\"openapi\":" in body, body)
    }
}
