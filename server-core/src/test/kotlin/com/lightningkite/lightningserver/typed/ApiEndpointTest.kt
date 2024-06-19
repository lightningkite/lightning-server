package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.parse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer
import org.junit.Test
import kotlin.test.assertEquals

class ApiEndpointTest {
    init {
        TestSettings
    }

    @Test
    fun testApiEndpoint0() {
        val httpEndpoint = ServerPath("test-api-0").typed.post

        runBlocking {
            val response = ApiEndpoint(
                route = httpEndpoint,
                authOptions = noAuth,
                inputType = serializer(),
                outputType = serializer(),
                summary = "",
                description = "",
                examples = listOf(),
                successCode = HttpStatus.OK,
                errorCases = listOf(),
                implementation = { value: Int -> value + 1 },
            ).invoke(
                HttpRequest(
                    endpoint = httpEndpoint.endpoint, body = HttpContent.Text("1", type = ContentType.Application.Json)
                )
            )
            assertEquals(expected = response.status, actual = HttpStatus.OK)
            assertEquals(expected = response.body?.parse<Int>(), actual = 2)
        }
    }

    @Test
    fun testApiEndpoint1() {
        val httpEndpoint = ServerPath("test-api-1").arg<String>("first").post

        runBlocking {
            val response = ApiEndpoint(
                route = httpEndpoint,
                authOptions = noAuth,
                inputType = serializer(),
                outputType = serializer(),
                summary = "",
                description = "",
                examples = listOf(),
                successCode = HttpStatus.OK,
                errorCases = listOf(),
                implementation = { value: Int -> value + 1 },
            ).invoke(
                HttpRequest(
                    parts = mapOf("first" to "test"),
                    endpoint = httpEndpoint.endpoint, body = HttpContent.Text("1", type = ContentType.Application.Json)
                )
            )
            assertEquals(expected = response.status, actual = HttpStatus.OK)
            assertEquals(expected = response.body?.parse<Int>(), actual = 2)
        }
    }

    @Test
    fun testApiEndpoint2() {
        val httpEndpoint = ServerPath("test-api-1").arg<String>("first").arg("second", Int.serializer()).post

        runBlocking {
            val response = ApiEndpoint(
                route = httpEndpoint,
                authOptions = noAuth,
                inputType = serializer(),
                outputType = serializer(),
                summary = "",
                description = "",
                examples = listOf(),
                successCode = HttpStatus.OK,
                errorCases = listOf(),
                implementation = { value: Int -> value + 1 },
            ).invoke(
                HttpRequest(
                    parts = mapOf("first" to "test", "second" to "3"),
                    endpoint = httpEndpoint.endpoint, body = HttpContent.Text("1", type = ContentType.Application.Json)
                )
            )
            assertEquals(expected = response.status, actual = HttpStatus.OK)
            assertEquals(expected = response.body?.parse<Int>(), actual = 2)
        }
    }
}
