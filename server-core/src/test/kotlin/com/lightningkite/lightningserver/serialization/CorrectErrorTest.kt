package com.lightningkite.lightningserver.serialization

import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.typed.typed
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.Test
import kotlin.test.assertEquals

class CorrectErrorTest {
    @Serializable
    data class TestModel(
        val number: Int = 0
    )

    @Test
    fun testBadRequest() {
        TestSettings
        val t = ServerPath("test").post.typed(
            summary = "Test endpoint",
            errorCases = listOf(),
            implementation = { user: Unit, input: TestModel -> input }
        )
        runBlocking {
            t.route.endpoint.test(
                body = HttpContent.Text("""{"number": 2}""", ContentType.Application.Json),
                headers = HttpHeaders(HttpHeader.Accept to ContentType.Application.Json.toString())
            ).let {
                assertEquals(HttpStatus.OK, it.status)
                assertEquals(TestModel(number = 2), it.body?.parse())
            }
            t.route.endpoint.test(
                body = HttpContent.Text("""{"number": "asdf"}""", ContentType.Application.Json),
                headers = HttpHeaders(HttpHeader.Accept to ContentType.Application.Json.toString())
            ).let {
                assertEquals(HttpStatus.BadRequest, it.status)
                println(it.body?.text())
            }
        }
    }
}