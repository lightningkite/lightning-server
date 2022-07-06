@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.jsonschema

import com.lightningkite.lightningserver.auth.JwtSigner
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.notifications.NotificationSettings
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningdb.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.util.*

@Serializable
@DatabaseModel
data class Post(
    override val _id: UUID = UUID.randomUUID(),
    val author: String,
    val title: String,
    val content: String
) : HasId<UUID>
