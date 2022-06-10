@file:UseContextualSerialization(UUID::class)
package com.lightningkite.ktorbatteries.auth

import com.lightningkite.ktorbatteries.SetOnce
import com.lightningkite.ktorbatteries.db.database
import com.lightningkite.ktorbatteries.email.EmailSettings
import com.lightningkite.ktorbatteries.exceptions.ExceptionSettings
import com.lightningkite.ktorbatteries.files.FilesSettings
import com.lightningkite.ktorbatteries.files.configureFiles
import com.lightningkite.ktorbatteries.files.publicUrl
import com.lightningkite.ktorbatteries.files.upload
import com.lightningkite.ktorbatteries.logging.LoggingSettings
import com.lightningkite.ktorbatteries.mongo.MongoSettings
import com.lightningkite.ktorbatteries.serialization.configureSerialization
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import com.lightningkite.ktorbatteries.typed.parseUrlPartOrBadRequest
import com.lightningkite.ktordb.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
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
    data class TestUser(override val _id: UUID = UUID.randomUUID(), override val email: String = ""): HasId<UUID>, HasEmail
    @Serializable
    data class Settings(
        val general: GeneralServerSettings = GeneralServerSettings(),
        val auth: AuthSettings = AuthSettings(),
        val files: FilesSettings = FilesSettings(),
        val logging: LoggingSettings = LoggingSettings(),
        val mongo: MongoSettings = MongoSettings("test"),
        val exception: ExceptionSettings = ExceptionSettings(),
        val email: EmailSettings = EmailSettings(),
    )
    @Test fun testSelf() {
        lateinit var settings: Settings
        SetOnce.allowOverwrite {
            settings = Settings()
        }
        testApplication {
            application {
                settings  //wtf, why do we need this?  If I delete it the test stops passing
                configureSerialization()
                configureFiles()
                configureAuth(onNewUser = { TestUser(email = it) })
            }
            val client = createClient {
                install(ContentNegotiation) {
                    json(Json {
                        serializersModule = ClientModule
                    })
                }
            }
            val user = TestUser(email = "test@test.com")
            database.collection<TestUser>().insertOne(user)
            val token = makeToken(user._id.toString())
            val self = client.get("/auth/self") {
                header("Authorization", "Bearer $token")
                accept(ContentType.Application.Json)
                println(headers.entries().joinToString())
            }.body<TestUser>()
            assertEquals(user, self)
        }
    }

}