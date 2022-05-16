@file:UseContextualSerialization(Instant::class, UUID::class, ServerFile::class)

package com.lightningkite.ktorbatteries.demo

import com.lightningkite.ktorbatteries.auth.*
import com.lightningkite.ktorbatteries.client
import com.lightningkite.ktorbatteries.db.adminIndex
import com.lightningkite.ktorbatteries.db.adminPages
import com.lightningkite.ktorbatteries.db.autoCollection
import com.lightningkite.ktorbatteries.db.database
import com.lightningkite.ktorbatteries.email.EmailSettings
import com.lightningkite.ktorbatteries.files.FilesSettings
import com.lightningkite.ktorbatteries.files.configureFiles
import com.lightningkite.ktorbatteries.jsonschema.JsonSchema
import com.lightningkite.ktorbatteries.logging.LoggingSettings
import com.lightningkite.ktorbatteries.mongo.MongoSettings
import com.lightningkite.ktorbatteries.mongo.mongoDb
import com.lightningkite.ktorbatteries.serialization.Serialization
import com.lightningkite.ktorbatteries.serialization.configureSerialization
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import com.lightningkite.ktorbatteries.settings.loadSettings
import com.lightningkite.ktorbatteries.settings.runServer
import com.lightningkite.ktorbatteries.typed.*
import com.lightningkite.ktordb.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.Principal
import io.ktor.server.plugins.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.util.*
import kotlinx.serialization.*
import java.io.File
import java.lang.Exception
import java.time.Instant
import java.util.*

@Serializable
@DatabaseModel
data class TestModel(
    override val _id: UUID = UUID.randomUUID(),
    val timestamp: Instant = Instant.now(),
    val name: String = "No Name",
    val number: Int = 3123,
    @JsonSchema.Format("jodit") val content: String = "",
    val file: ServerFile? = null
) : HasId<UUID>

@Serializable
@DatabaseModel
data class User(
    override val _id: UUID = UUID.randomUUID(),
    override val email: String
): HasId<UUID>, HasEmail

val TestModel.Companion.table get() = database.collection<TestModel>("TestModel")

@Serializable
data class Settings(
    val general: GeneralServerSettings = GeneralServerSettings(),
    val auth: AuthSettings = AuthSettings(),
    val files: FilesSettings = FilesSettings(),
    val logging: LoggingSettings = LoggingSettings(),
    val mongo: MongoSettings = MongoSettings(),
    val email: EmailSettings = EmailSettings()
)

fun main(vararg args: String) {
    loadSettings(File("settings.yaml")) { Settings() }
    runServer {
        configureFiles()
        configureSerialization()
        configureAuth(onNewUser = { User(email = it) })
        install(WebSockets)
        install(StatusPages) {
            exception<Exception> { call, cause ->
                cause.printStackTrace()
                call.respondText(cause.message ?: "Unknown Error")
            }
        }
        routing {
            authenticate(optional = true) {
                autoCollection("test-model", { TestModel() }, { user: String? -> TestModel.table })
                autoCollection("email", { TestModel() }, { user: String? -> TestModel.table })
                get {
                    val user = call.user<User>()
                    println("User retrieved is $user")
                    call.respondText("Welcome, ${user?.email ?: "anon"}!")
                }
                adminIndex()
                apiHelp()

                get(
                    path = "test-primitive",
                    summary = "Get Test Primitive",
                    errorCases = listOf(),
                    implementation = { input: Unit -> "42 is great" }
                )
            }
        }
        println(SDK.test())
    }
}