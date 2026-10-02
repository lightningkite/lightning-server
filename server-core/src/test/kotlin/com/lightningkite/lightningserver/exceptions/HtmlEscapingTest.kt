package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.http.Http
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.http.HttpRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse

class HtmlEscapingTest {
    private val payload = "<script>alert(1)</script>"

    // Browsers send `Accept: text/html`, and exception messages often quote request data.
    @Test
    fun exceptionMessageIsEscapedInHtmlResponse(): Unit = runBlocking {
        TestSettings
        val request = HttpRequest(
            endpoint = HttpEndpoint("", HttpMethod.GET),
            headers = HttpHeaders("Accept" to "text/html"),
        )
        val body = NotFoundException(detail = "", message = payload).toResponse(request).body!!.text()
        assertFalse(payload in body, body)
    }

    @Test
    fun notFoundPageEscapesPathInDebugMode(): Unit = runBlocking {
        TestSettings
        val endpoint = HttpEndpoint("/$payload", HttpMethod.GET)
        val body = Http.notFound(endpoint, HttpRequest(endpoint)).body!!.text()
        assertFalse(payload in body, body)
    }
}
