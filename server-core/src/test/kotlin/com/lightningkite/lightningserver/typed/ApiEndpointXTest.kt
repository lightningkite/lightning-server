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

class ApiEndpointXTest {
    init {
        TestSettings
    }

    @Test
    fun testSelf() {
        val httpEndpoint = HttpEndpoint("post", HttpMethod("post"))

        runBlocking {
            val response = ApiEndpointX(
                route = httpEndpoint,
                authInfo = AuthInfo(),
                inputType = serializer(),
                outputType = serializer(),
                summary = "",
                errorCases = listOf(),
                routeTypes = mapOf(
                    "test-path1" to serializer<String>(),
                    "test-path2" to serializer(),
                    "test-path3" to serializer()
                ),
                implementation = { _: Unit, value: Int, _: Map<String, Any?> -> value + 1 },
            ).invoke(
                HttpRequest(
                    endpoint = httpEndpoint,
                    body = HttpContent.Text("1", type = ContentType.Application.Json),
                    parts = mapOf(
                        "test-path1" to "",
                        "test-path2" to "",
                        "test-path3" to "",
                    )
                )
            )
            assertEquals(expected = response.status, actual = HttpStatus.OK)
            assertEquals(expected = response.body?.parse<Int>(), actual = 2)
        }
    }
}
