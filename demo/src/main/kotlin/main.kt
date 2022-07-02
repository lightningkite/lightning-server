@file:UseContextualSerialization(Instant::class, UUID::class, ServerFile::class)

package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.db.*
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.exceptions.ExceptionSettings
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.logging.LoggingSettings
import com.lightningkite.lightningserver.mongo.MongoSettings
import com.lightningkite.lightningserver.mongo.mongoDb
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningserver.settings.loadSettings
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.core.routing
import com.lightningkite.lightningserver.http.Http
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.handler
import com.lightningkite.lightningserver.ktor.runServer
import com.lightningkite.lightningserver.serverhealth.healthCheck
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.Principal
import io.ktor.server.plugins.*
import io.ktor.server.plugins.callloging.*
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
    @JsonSchemaFormat("jodit") val content: String = "",
    val file: ServerFile? = null
) : HasId<UUID>

@Serializable
@DatabaseModel
data class User(
    override val _id: UUID = UUID.randomUUID(),
    override val email: String
) : HasId<UUID>, HasEmail

val TestModel.Companion.table get() = database.collection<TestModel>("TestModel")

@Serializable
data class Settings(
    val general: GeneralServerSettings = GeneralServerSettings(),
    val auth: AuthSettings = AuthSettings(),
    val files: FilesSettings = FilesSettings(),
    val logging: LoggingSettings = LoggingSettings(),
    val database: DatabaseSettings = DatabaseSettings(),
    val email: EmailSettings = EmailSettings(),
    val exception: ExceptionSettings = ExceptionSettings(),
)

fun main(vararg args: String) {
    loadSettings(File("settings.yaml")) { Settings() }
    println("Settings loaded")
    prepareModels()
    routing {
        path("test-model") {
            path("rest").restApi { user: User? -> TestModel.table }
            path("admin").adminPages(::TestModel) { user: User? -> TestModel.table }
        }
        path("docs").apiHelp()
        path("health").healthCheck(listOf(
            EmailSettings.instance,
            ExceptionSettings.instance,
            FilesSettings.instance,
            DatabaseSettings.instance,
        )) { user: Unit -> true }
        path("test-primitive").get.typed(
            summary = "Get Test Primitive",
            errorCases = listOf(),
            implementation = { user: User?, input: Unit -> "42 is great" }
        )
        path("die").get.handler { throw Exception("OUCH") }
    }
    runServer()
}