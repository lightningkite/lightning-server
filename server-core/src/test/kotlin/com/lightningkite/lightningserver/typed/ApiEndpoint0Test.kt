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

class ApiEndpoint0Test {
    init {
        TestSettings
    }

    @Test
    fun testSelf() {
        val httpEndpoint = HttpEndpoint("", HttpMethod(""))

        runBlocking {
            val response = ApiEndpoint0(
                route = httpEndpoint,
                authInfo = AuthInfo(),
                inputType = serializer(),
                outputType = serializer(),
                summary = "",
                errorCases = listOf(),
                implementation = { _: Unit, value: Int -> value + 1 },
            ).invoke(
                HttpRequest(
                    endpoint = httpEndpoint, body = HttpContent.Text("1", type = ContentType.Application.Json)
                )
            )
            assertEquals(expected = response.status, actual = HttpStatus.OK)
            assertEquals(expected = response.body?.parse<Int>(), actual = 2)
        }
    }
}
