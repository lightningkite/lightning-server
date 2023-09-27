package com.lightningkite.lightningserver.auth.oauth

import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import kotlinx.serialization.Serializable

@GenerateDataClassPaths
@Serializable
data class OauthClient(
    override val _id: String,
    val scopes: Set<String>,
    val secretHash: String,
) : HasId<String> {

}