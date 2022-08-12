@file:UseContextualSerialization(UUID::class)

package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.auth.AuthInfo
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.parse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.UseContextualSerialization
import kotlinx.serialization.serializer
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals

class ApiEndpoint2Test {
    init {
        TestSettings
    }

    @Test
    fun testSelf() {
        val httpEndpoint = HttpEndpoint("post", HttpMethod("post"))

        runBlocking {
            val response = ApiEndpoint2(
                route = httpEndpoint,
                authInfo = AuthInfo(),
                inputType = serializer(),
                pathName = "test-path1",
                pathType = serializer(),
                path2Name = "test-path2",
                path2Type = serializer(),
                outputType = serializer(),
                summary = "",
                errorCases = listOf(),
                implementation = { _: Unit, _: String, _: String, value: Int -> value + 1 },
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
