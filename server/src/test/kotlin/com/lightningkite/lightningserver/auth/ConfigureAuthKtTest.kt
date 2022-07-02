@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.SetOnce
import com.lightningkite.lightningserver.db.database
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.exceptions.ExceptionSettings
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.files.publicUrl
import com.lightningkite.lightningserver.files.upload
import com.lightningkite.lightningserver.logging.LoggingSettings
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningserver.typed.parseUrlPartOrBadRequest
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.db.database
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.test
import com.lightningkite.lightningserver.ktor.lightningServer
import com.lightningkite.lightningserver.serialization.parse
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.serialization.json.Json
import org.apache.commons.vfs2.VFS
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals


class ConfigureAuthKtTest {
    @Serializable
    data class TestUser(override val _id: UUID = UUID.randomUUID(), override val email: String = ""): HasId<UUID>,
        HasEmail
    @Serializable
    data class Settings(
        val general: GeneralServerSettings = GeneralServerSettings(),
        val auth: AuthSettings = AuthSettings(),
        val files: FilesSettings = FilesSettings(),
        val logging: LoggingSettings = LoggingSettings(),
        val database: DatabaseSettings = DatabaseSettings("ram"),
        val exception: ExceptionSettings = ExceptionSettings(),
        val email: EmailSettings = EmailSettings(),
    )
    @Test fun testSelfKtor() {
        lateinit var settings: Settings
        SetOnce.allowOverwrite {
            settings = Settings()
        }
        val authEndpoints = ServerPath("auth").authEndpoints(onNewUser = { TestUser(email = it) })
        val user = TestUser(email = "test@test.com")
        testApplication {
            application {
                settings  //wtf, why do we need this?  If I delete it the test stops passing
                lightningServer()
            }
            val client = createClient {
                install(ContentNegotiation) {
                    json(Json {
                        serializersModule = ClientModule
                    })
                }
            }
            database.collection<TestUser>().insertOne(user)
            val token = AuthSettings.instance.token(user._id)
            perfTest {
                val self = client.get("/auth/self") {
                    header("Authorization", "Bearer $token")
                    accept(ContentType.Application.Json)
                }.body<TestUser>()
                assertEquals(user, self)
            }.also { println("Time: $it ms for testSelfKtor")}
        }
    }
    @Test fun testSelf() {
        lateinit var settings: Settings
        SetOnce.allowOverwrite {
            settings = Settings()
        }
        val authEndpoints = ServerPath("auth").authEndpoints(onNewUser = { TestUser(email = it) })
        val user = TestUser(email = "test@test.com")
        runBlocking {
            database.collection<TestUser>().insertOne(user)
            val token = AuthSettings.instance.token(user._id)
            perfTest {
                val self = authEndpoints.path("self").get.test(
                    headers = HttpHeaders(
                        mapOf(
                            HttpHeader.Authorization to "Bearer $token",
                            HttpHeader.Accept to ContentType.Application.Json.toString()
                        )
                    )
                ).body!!.parse<TestUser>()
                assertEquals(user, self)
            }.also { println("Time: $it ms for testSelf")}
        }
    }

    private inline fun perfTest(action: ()->Unit): Long {
        val s = System.currentTimeMillis()
        repeat(10000) { action() }
        return System.currentTimeMillis() - s
    }
}
