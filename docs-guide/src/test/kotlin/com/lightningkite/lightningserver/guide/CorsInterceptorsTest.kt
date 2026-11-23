package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.EngineApi
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.guide.samples.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.runtime.Execution
import com.lightningkite.lightningserver.runtime.handleRoot
import com.lightningkite.lightningserver.runtime.test.*
import kotlin.test.Test
import kotlin.test.assertEquals

class CorsInterceptorsTest {
    @Test fun `CORS allows the configured origin and rejects others`() { corsAllowedOriginTest() }
    @Test fun `interceptors run in install order`() { interceptorOrderTest() }

    @Test fun `interceptors inside MaintenanceMode run when it is off`() = InterceptedServer.testBlocking(settings = {}) {
        assertEquals("api-1", InterceptedServer.hello.test()["X-Served-By"])
    }

    @Test fun `cacheLength is an ISO-8601 duration in settings files`() {
        val settings = kotlinx.serialization.json.Json.decodeFromString(
            com.lightningkite.lightningserver.cors.CorsSettings.serializer(),
            """{"cacheLength": "PT1H"}""",
        )
        assertEquals(kotlin.time.Duration.parse("1h"), settings.cacheLength)
    }

    private operator fun HttpResponse.get(header: String) = headers[header]?.root

    // Backs the chapter's description of preflight handling.
    @Test fun `preflight is answered by the interceptor`() = CorsServer.testBlocking(settings = {}) {
        val response = preflight()
        assertEquals(HttpStatus.NoContent, response.status)
        assertEquals("GET,HEAD", response[HttpHeader.AccessControlAllowMethods])
        assertEquals("traceparent", response[HttpHeader.AccessControlAllowHeaders])
        assertEquals("10", response[HttpHeader.AccessControlMaxAge])
        assertEquals("true", response[HttpHeader.AccessControlAllowCredentials])
    }

    @OptIn(EngineApi::class)
    context(runner: TestRunner<*>)
    private suspend fun preflight(): HttpResponse =
        runner.handleRoot(
            HttpRequest(
                RawHttpEndpoint("/api/hello", HttpMethod.OPTIONS),
                queryParameters = QueryParameters.EMPTY,
                headers = HttpHeaders {
                    add(HttpHeader.Origin, "https://app.example.com")
                    add(HttpHeader.AccessControlRequestMethod, "GET")
                    add(HttpHeader.AccessControlRequestHeaders, "traceparent")
                },
                domain = "localhost",
                protocol = "http",
                sourceIp = "local",
            ),
            Execution.ID.generate(),
        )
}
