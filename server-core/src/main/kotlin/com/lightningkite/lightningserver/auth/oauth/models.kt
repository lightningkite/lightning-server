@file:UseContextualSerialization(ServerFile::class, Instant::class)
package com.lightningkite.lightningserver.auth.oauth

import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.References
import com.lightningkite.lightningdb.ServerFile
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.datetime.Instant
import java.util.UUID

@GenerateDataClassPaths
@Serializable
data class OauthClient(
    override val _id: String,
    val niceName: String,
    val logo: ServerFile? = null,
    val scopes: Set<String> = setOf(),
    val secrets: Set<OauthClientSecret> = setOf(),
    val redirectUris: Set<String> = setOf(),
) : HasId<String> {

}

@GenerateDataClassPaths
@Serializable
data class OauthClientSecret(
    val createdAt: Instant = Clock.System.now(),
    val masked: String,
    val secretHash: String,
    val disabledAt: Instant? = null,
)