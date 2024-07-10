package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningdb.test.ValidatedModel
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ApiEndpointValidateTest {
    @Test
    fun test() {
        runBlocking {
            TestSettings.sample4(
                HttpRequest(
                    endpoint = TestSettings.sample4.route.endpoint,
                    parts = mapOf(),
                    body = HttpContent.json(ValidatedModel("Sample Name"))
                )
            ).also { println(it) }
            assertFailsWith<BadRequestException> {
                TestSettings.sample4(
                    HttpRequest(
                        endpoint = TestSettings.sample4.route.endpoint,
                        parts = mapOf(),
                        body = HttpContent.json(ValidatedModel("Invalid!"))
                    )
                ).also { println(it) }
            }
            assertFailsWith<BadRequestException> {
                TestSettings.sample4(
                    HttpRequest(
                        endpoint = TestSettings.sample4.route.endpoint,
                        parts = mapOf(),
                        body = HttpContent.json(ValidatedModel("Way toooooooooooooo looooooong"))
                    )
                ).also { println(it) }
            }
        }
    }
}