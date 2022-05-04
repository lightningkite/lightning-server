@file:UseContextualSerialization(Instant::class, UUID::class)

package com.lightningkite.ktorbatteries.demo

import com.lightningkite.ktorbatteries.auth.*
import com.lightningkite.ktorbatteries.client
import com.lightningkite.ktorbatteries.db.adminPages
import com.lightningkite.ktorbatteries.db.database
import com.lightningkite.ktorbatteries.db.exposeReadWrite
import com.lightningkite.ktorbatteries.files.FilesSettings
import com.lightningkite.ktorbatteries.files.configureFiles
import com.lightningkite.ktorbatteries.jsonschema.JsonSchema
import com.lightningkite.ktorbatteries.logging.LoggingSettings
import com.lightningkite.ktorbatteries.mongo.MongoSettings
import com.lightningkite.ktorbatteries.mongo.mongoDb
import com.lightningkite.ktorbatteries.serialization.configureSerialization
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import com.lightningkite.ktorbatteries.settings.loadSettings
import com.lightningkite.ktorbatteries.settings.runServer
import com.lightningkite.ktorkmongo.*
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
    @IsFile val file: String? = null
) : HasId

val TestModel.Companion.table get() = database.collection<TestModel>("TestModel")

data class DirectPrincipal(val id: String) : Principal

@Serializable
data class Settings(
    val general: GeneralServerSettings = GeneralServerSettings(),
    val auth: AuthSettings = AuthSettings(),
    val files: FilesSettings = FilesSettings(),
    val logging: LoggingSettings = LoggingSettings(),
    val mongo: MongoSettings = MongoSettings()
)

fun main(vararg args: String) {
    fixUuidSerialization()
    loadSettings(File("settings.yaml")) { Settings() }
    database = mongoDb
    runServer {
        install(IgnoreTrailingSlash)
        configureFiles()
        configureSerialization()
        authentication {
            quickJwt { id -> DirectPrincipal(id) }
        }
        install(StatusPages) {
            exception<Exception> { call, cause -> call.respondText(cause.message ?: "Unknown Error") }
        }
        routing {
            authenticate(optional = true) {
                route("admin") {
                    adminPages(
                        TestModel.table,
                        defaultItem = { TestModel() }) { user: DirectPrincipal? -> SecurityRules.AllowAll() }
                }
                route("rest") {
                    exposeReadWrite(TestModel.table) { user: DirectPrincipal? -> SecurityRules.AllowAll() }
                }
                get {
                    call.respondText("Welcome, ${call.principal<DirectPrincipal>()?.id}!")
                }
                oauthGoogle(GeneralServerSettings.instance.publicUrl) { it }
                oauthGithub(GeneralServerSettings.instance.publicUrl) { it }
                oauthApple(GeneralServerSettings.instance.publicUrl) { it }
            }
        }
    }
}