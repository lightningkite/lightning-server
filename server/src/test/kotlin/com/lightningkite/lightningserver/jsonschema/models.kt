@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.jsonschema

import com.lightningkite.lightningserver.auth.AuthSettings
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.mongo.MongoSettings
import com.lightningkite.lightningserver.mongo.mongoDb
import com.lightningkite.lightningserver.notifications.NotificationSettings
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningdb.*
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
) : HasId<UUID>

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