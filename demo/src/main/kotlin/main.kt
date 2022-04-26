@file:UseContextualSerialization(Instant::class, UUID::class)
package com.lightningkite.ktorbatteries.demo

import com.lightningkite.ktorbatteries.auth.AuthSettings
import com.lightningkite.ktorbatteries.auth.quickJwt
import com.lightningkite.ktorbatteries.db.adminPages
import com.lightningkite.ktorbatteries.db.database
import com.lightningkite.ktorbatteries.files.FilesSettings
import com.lightningkite.ktorbatteries.files.configureFiles
import com.lightningkite.ktorbatteries.logging.LoggingSettings
import com.lightningkite.ktorbatteries.mongo.MongoSettings
import com.lightningkite.ktorbatteries.mongo.mongoDb
import com.lightningkite.ktorbatteries.serialization.configureSerialization
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import com.lightningkite.ktorbatteries.settings.loadSettings
import com.lightningkite.ktorbatteries.settings.runServer
import com.lightningkite.ktorkmongo.*
import io.ktor.auth.*
import io.ktor.routing.*
import kotlinx.serialization.*
import java.io.File
import java.time.Instant
import java.util.*

@Serializable
@DatabaseModel
data class TestModel(
    override val _id: UUID = UUID.randomUUID(),
    val timestamp: Instant = Instant.now(),
    val name: String = "No Name",
    val number: Int = 3123
): HasId

@Serializable
@DatabaseModel
data class User(
    override val _id: UUID = UUID.randomUUID(),
    val email: String
): HasId

val TestModel.Companion.mongo get() = database.collection<TestModel>("TestModel")
val User.Companion.mongo get() = database.collection<User>("User")

data class UserPrincipal(val user: User): Principal

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
        configureFiles()
        configureSerialization()
        authentication {
            quickJwt { id ->
                User.mongo
                    .get(UUID.fromString(id))
                    ?.let { UserPrincipal(it) }
            }
        }
        routing {
            adminPages(TestModel.mongo, defaultItem = {TestModel()}) { user: UserPrincipal? -> SecurityRules.AllowAll() }
        }
    }
}