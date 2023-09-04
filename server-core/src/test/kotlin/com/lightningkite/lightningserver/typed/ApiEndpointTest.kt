package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.parse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.serializer
import org.junit.Test
import kotlin.test.assertEquals

class ApiEndpointTest {
    init {
        TestSettings
    }

    @Test
    fun testApiEndpoint0() {
        val httpEndpoint = HttpEndpoint("", HttpMethod(""))

        runBlocking {
            val response = ApiEndpoint0(
                route = httpEndpoint,
                authOptions = setOf(null),
                inputType = serializer(),
                outputType = serializer(),
                summary = "",
                errorCases = listOf(),
                implementation = { _: RequestAuth<*>?, value: Int -> value + 1 },
            ).invoke(
                HttpRequest(
                    endpoint = httpEndpoint, body = HttpContent.Text("1", type = ContentType.Application.Json)
                )
            )
            assertEquals(expected = response.status, actual = HttpStatus.OK)
            assertEquals(expected = response.body?.parse<Int>(), actual = 2)
        }
    }

    @Test
    fun testApiEndpoint1() {
        val httpEndpoint = HttpEndpoint("post", HttpMethod("post"))

        runBlocking {
            val response = ApiEndpoint1(
                route = httpEndpoint,
                authOptions = setOf(null),
                inputType = serializer(),
                path1Name = "test-path",
                path1Type = serializer(),
                outputType = serializer(),
                summary = "",
                errorCases = listOf(),
                implementation = { _: RequestAuth<*>?, _: String, value: Int -> value + 1 },
            ).invoke(
                HttpRequest(
                    endpoint = httpEndpoint,
                    body = HttpContent.Text("1", type = ContentType.Application.Json),
                    parts = mapOf("test-path" to "")
                )
            )
            assertEquals(expected = response.status, actual = HttpStatus.OK)
            assertEquals(expected = response.body?.parse<Int>(), actual = 2)
        }
    }

    @Test
    fun testApiEndpoint2() {
        val httpEndpoint = HttpEndpoint("post", HttpMethod("post"))

        runBlocking {
            val response = ApiEndpoint2(
                route = httpEndpoint,
                authOptions = setOf(null),
                inputType = serializer(),
                path1Name = "test-path1",
                path1Type = serializer(),
                path2Name = "test-path2",
                path2Type = serializer(),
                outputType = serializer(),
                summary = "",
                errorCases = listOf(),
                implementation = { _: RequestAuth<*>?, _: String, _: String, value: Int -> value + 1 },
            ).invoke(
                HttpRequest(
                    endpoint = httpEndpoint,
                    body = HttpContent.Text("1", type = ContentType.Application.Json),
                    parts = mapOf("test-path1" to "", "test-path2" to "")
                )
            )
            assertEquals(expected = response.status, actual = HttpStatus.OK)
            assertEquals(expected = response.body?.parse<Int>(), actual = 2)
        }
    }
}
