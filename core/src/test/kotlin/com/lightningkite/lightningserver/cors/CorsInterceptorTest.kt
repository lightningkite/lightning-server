package com.lightningkite.lightningserver.cors

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.services.data.TypedData
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CorsInterceptorTest {

    object TestServer: ServerBuilder() {
        val cors = setting("cors", CorsSettings(
            limitToDomains = listOf("example.com")
        ))
        init {
            install(CorsInterceptor(cors))
        }
        val testEndpoint = path.path("sample").post bind HttpHandler {
            HttpResponse.plainText("OK", HttpStatus.OK)
        }
        init { registerBasicMediaTypeCoders() }
    }

    @Test
    fun cors() {
        TestServer.test(
            settings = {}
        ) {
            runBlocking {
                serverRuntime.handle(HttpRequest(
                    path = RawHttpEndpoint("/sample", HttpMethod.POST),
                    queryParameters = QueryParameters.EMPTY,
                    headers = HttpHeaders {
                        set(HttpHeader.Origin, "example.com")
                    },
                    domain = "example.com",
                    protocol = "https",
                    sourceIp = "localhost",
                    body = TypedData.text("plain", MediaType.Text.Plain)
                )).also { println(it.status) }.also { assertEquals(2, it.status.code / 100) }

                serverRuntime.handle(HttpRequest(
                    path = RawHttpEndpoint("/sample", HttpMethod.POST),
                    queryParameters = QueryParameters.EMPTY,
                    headers = HttpHeaders {
                    },
                    domain = "example.com",
                    protocol = "https",
                    sourceIp = "localhost",
                    body = TypedData.text("plain", MediaType.Text.Plain)
                )).also { println(it.status) }.also { assertEquals(2, it.status.code / 100) }

                serverRuntime.handle(HttpRequest(
                    path = RawHttpEndpoint("/sample", HttpMethod.POST),
                    queryParameters = QueryParameters.EMPTY,
                    headers = HttpHeaders {
                        set(HttpHeader.Origin, "notincluded.com")
                    },
                    domain = "other.com",
                    protocol = "https",
                    sourceIp = "localhost",
                    body = TypedData.text("plain", MediaType.Text.Plain)
                )).also { println(it.status) }.also { assertEquals(4, it.status.code / 100) }
            }
        }
    }
}