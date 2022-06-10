package com.lightningkite.ktorbatteries.serialization

import com.lightningkite.ktorbatteries.SetOnce
import com.lightningkite.ktorbatteries.auth.AuthSettings
import com.lightningkite.ktorbatteries.auth.configureAuth
import com.lightningkite.ktorbatteries.auth.makeToken
import com.lightningkite.ktorbatteries.db.database
import com.lightningkite.ktorbatteries.email.EmailSettings
import com.lightningkite.ktorbatteries.exceptions.ExceptionSettings
import com.lightningkite.ktorbatteries.files.FilesSettings
import com.lightningkite.ktorbatteries.files.configureFiles
import com.lightningkite.ktorbatteries.logging.LoggingSettings
import com.lightningkite.ktorbatteries.mongo.MongoSettings
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import com.lightningkite.ktorbatteries.typed.post
import com.lightningkite.ktordb.ClientModule
import com.lightningkite.ktordb.HasEmail
import com.lightningkite.ktordb.HasId
import com.lightningkite.ktordb.collection
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CorrectErrorTest {
    @Serializable
    data class TestModel(
        val number: Int = 0
    )
    @Test fun testBadRequest() {
        testApplication {
            application {
                configureSerialization()
                this.routing {
                    post(
                        path = "test",
                        summary = "Test endpoint",
                        errorCases = listOf(),
                        implementation = { input: TestModel -> input }
                    )
                }
            }
            val client = createClient {
                this.expectSuccess = false
                install(ContentNegotiation) {
                    json(Json {
                        serializersModule = ClientModule
                    })
                }
            }
            val correct = client.post("/test") {
                setBody(buildJsonObject { put("number", 2) })
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                println(headers.entries().joinToString())
            }
            assertTrue(correct.status.isSuccess())
            val wrong = client.post("/test") {
                setBody(buildJsonObject { put("number", "wrongo") })
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                println(headers.entries().joinToString())
            }
            assertFalse(wrong.status.isSuccess())
        }
    }
}