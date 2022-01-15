package com.lightningkite.ktorbatteries.jsonschema

import com.lightningkite.ktorbatteries.auth.AuthSettings
import com.lightningkite.ktorbatteries.email.EmailSettings
import com.lightningkite.ktorbatteries.files.FilesSettings
import com.lightningkite.ktorbatteries.mongo.MongoSettings
import com.lightningkite.ktorbatteries.notifications.NotificationSettings
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import com.lightningkite.ktorkmongo.MongoCollection
import kotlinx.serialization.Serializable

@Serializable
@MongoCollection
data class Post(
    val author: String,
    val title: String,
    val content: String
)

@Serializable
data class Settings(
    val server: GeneralServerSettings = GeneralServerSettings(),
    val notification: NotificationSettings = NotificationSettings(),
    val mongo: MongoSettings = MongoSettings(),
    val auth: AuthSettings = AuthSettings(),
    val email: EmailSettings = EmailSettings(),
    val files: FilesSettings = FilesSettings(),
)