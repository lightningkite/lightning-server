// by Claude
package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.definition.GeneralServerSettings
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for HttpInterceptor functionality including chaining, instrumentation,
 * and the None interceptor.
 */
class HttpInterceptorTest {

    @Test
    fun `HttpInterceptor NoOp passes requests through unchanged`() {
        object : ServerBuilder() {
            init {
                registerBasicMediaTypeCoders()
                install(HttpInterceptor.NoOp)
            }

            val endpoint = path.path("test").get bind HttpHandler {
                HttpResponse.plainText("Hello from None")
            }
        }.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val response = endpoint.test()
                assertEquals(HttpStatus.OK, response.status)
                assertEquals("Hello from None", response.body!!.text())
            }
        }
    }

    @Test
    fun `HttpInterceptor can modify request before passing through`() {
        // Interceptor that adds a custom header to all requests
        val headerAddingInterceptor = HttpInterceptor { request, cont ->
            val modifiedRequest = request.copy(
                headers = request.headers.copy {
                    add("X-Added-Header", "intercepted")
                }
            )
            cont(modifiedRequest)
        }

        object : ServerBuilder() {
            init {
                registerBasicMediaTypeCoders()
                install(headerAddingInterceptor)
            }

            val endpoint = path.path("test").get bind HttpHandler { request ->
                val interceptedHeader = request.headers["X-Added-Header"]?.root
                HttpResponse.plainText("Header value: $interceptedHeader")
            }
        }.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val response = endpoint.test()
                assertEquals(HttpStatus.OK, response.status)
                assertEquals("Header value: intercepted", response.body!!.text())
            }
        }
    }

    @Test
    fun `HttpInterceptor can modify response after continuation`() {
        // Interceptor that adds a header to all responses
        val responseModifyingInterceptor = HttpInterceptor { request, cont ->
            val response = cont(request)
            response.copy(
                headers = response.headers.copy {
                    add("X-Intercepted", "true")
                }
            )
        }

        object : ServerBuilder() {
            init {
                registerBasicMediaTypeCoders()
                install(responseModifyingInterceptor)
            }

            val endpoint = path.path("test").get bind HttpHandler {
                HttpResponse.plainText("Hello")
            }
        }.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val response = endpoint.test()
                assertEquals(HttpStatus.OK, response.status)
                assertEquals("true", response.headers["X-Intercepted"]?.root)
            }
        }
    }

    @Test
    fun `HttpInterceptor can short-circuit and return early response`() {
        // Interceptor that blocks requests with certain header
        val blockingInterceptor = HttpInterceptor { request, cont ->
            if (request.headers["X-Block"]?.root == "true") {
                HttpResponse(
                    status = HttpStatus.Forbidden,
                    body = null
                )
            } else {
                cont(request)
            }
        }

        object : ServerBuilder() {
            init {
                registerBasicMediaTypeCoders()
                install(blockingInterceptor)
            }

            val endpoint = path.path("test").get bind HttpHandler {
                HttpResponse.plainText("Should not reach here")
            }
        }.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                // Request with blocking header
                val blockedResponse = endpoint.test(
                    headers = HttpHeaders {
                        add("X-Block", "true")
                    }
                )
                assertEquals(HttpStatus.Forbidden, blockedResponse.status)

                // Request without blocking header
                val allowedResponse = endpoint.test()
                assertEquals(HttpStatus.OK, allowedResponse.status)
                assertEquals("Should not reach here", allowedResponse.body!!.text())
            }
        }
    }

    @Test
    fun `Multiple interceptors execute in installation order`() {
        val executionOrder = mutableListOf<String>()

        val firstInterceptor = object : HttpInterceptor {
            override val name = "FirstInterceptor"
            context(runtime: ServerRuntime)
            override suspend fun intercept(
                request: HttpRequest<*>,
                cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse
            ): HttpResponse {
                executionOrder.add("first-before")
                val response = cont(request)
                executionOrder.add("first-after")
                return response
            }
        }

        val secondInterceptor = object : HttpInterceptor {
            override val name = "SecondInterceptor"
            context(runtime: ServerRuntime)
            override suspend fun intercept(
                request: HttpRequest<*>,
                cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse
            ): HttpResponse {
                executionOrder.add("second-before")
                val response = cont(request)
                executionOrder.add("second-after")
                return response
            }
        }

        object : ServerBuilder() {
            init {
                registerBasicMediaTypeCoders()
                install(firstInterceptor)
                install(secondInterceptor)
            }

            val endpoint = path.path("test").get bind HttpHandler {
                executionOrder.add("handler")
                HttpResponse.plainText("Done")
            }
        }.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                executionOrder.clear()
                endpoint.test()

                // Interceptors execute in order: first wraps second wraps handler
                assertEquals(
                    listOf("first-before", "second-before", "handler", "second-after", "first-after"),
                    executionOrder
                )
            }
        }
    }

    @Test
    fun `HttpInterceptor default name returns class name or anonymous`() {
        val namedInterceptor = object : HttpInterceptor {
            context(runtime: ServerRuntime)
            override suspend fun intercept(
                request: HttpRequest<*>,
                cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse
            ): HttpResponse = cont(request)
        }

        // Anonymous class name will contain $ and be non-null
        assertNotNull(namedInterceptor.name)

        // HttpInterceptor.NoOp has a specific name
        assertEquals("NoOp", HttpInterceptor.NoOp.name)
    }

    @Test
    fun `compileAndInstrument returns None for empty list`() {
        val compiled = emptyList<HttpInterceptor>().compileAndInstrument()
        assertEquals(HttpInterceptor.NoOp, compiled)
    }

    @Test
    fun `compileAndInstrument with single interceptor works`() {
        var called = false
        val singleInterceptor = HttpInterceptor { request, cont ->
            called = true
            cont(request)
        }

        object : ServerBuilder() {
            init {
                registerBasicMediaTypeCoders()
                install(singleInterceptor)
            }

            val endpoint = path.path("test").get bind HttpHandler {
                HttpResponse.plainText("Done")
            }
        }.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                called = false
                endpoint.test()
                assertTrue(called, "Single interceptor should be called")
            }
        }
    }

    @Test
    fun `HttpInterceptor lambda syntax works correctly`() {
        // Tests the fun interface syntax: HttpInterceptor { request, cont -> ... }
        val lambdaInterceptor = HttpInterceptor { request, cont ->
            val response = cont(request)
            response.copy(
                headers = response.headers.copy {
                    add("X-Lambda", "works")
                }
            )
        }

        object : ServerBuilder() {
            init {
                registerBasicMediaTypeCoders()
                install(lambdaInterceptor)
            }

            val endpoint = path.path("test").get bind HttpHandler {
                HttpResponse.plainText("Test")
            }
        }.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val response = endpoint.test()
                assertEquals("works", response.headers["X-Lambda"]?.root)
            }
        }
    }
}
