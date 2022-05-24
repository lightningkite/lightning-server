@file:UseContextualSerialization(UUID::class)
package com.lightningkite.ktorbatteries.jsonschema

import com.lightningkite.ktorbatteries.auth.AuthSettings
import com.lightningkite.ktorbatteries.email.EmailSettings
import com.lightningkite.ktorbatteries.files.FilesSettings
import com.lightningkite.ktorbatteries.mongo.MongoSettings
import com.lightningkite.ktorbatteries.mongo.mongoDb
import com.lightningkite.ktorbatteries.notifications.NotificationSettings
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import com.lightningkite.ktordb.*
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.serialization.UseSerializers
import java.util.*

@Serializable
@DatabaseModel
data class Post(
    override val _id: UUID = UUID.randomUUID(),
    val author: String,
    val title: String,
    val content: String
) : HasId<UUID>()

val Post.Companion.mongo get() = mongoDb.collection<Post>()

@Serializable
data class Settings(
    val server: GeneralServerSettings = GeneralServerSettings(),
    val notification: NotificationSettings = NotificationSettings(),
    val mongo: MongoSettings = MongoSettings(),
    val auth: AuthSettings = AuthSettings(),
    val email: EmailSettings = EmailSettings(),
    val files: FilesSettings = FilesSettings(),
)