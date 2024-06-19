package com.lightningkite.lightningserver.auth.oauth

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.ModelInfoWithDefault
import com.lightningkite.lightningserver.db.ModelRestEndpoints
import com.lightningkite.lightningserver.db.ModelSerializationInfo
import com.lightningkite.lightningserver.db.modelInfoWithDefault
import com.lightningkite.lightningserver.encryption.secureHash
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.typed.*
import kotlinx.serialization.serializer
import java.util.Base64
import kotlin.random.Random

class OauthClientEndpoints(
    path: ServerPath,
    val database: () -> Database,
    val maintainPermissions: AuthOptions<*> = Authentication.isSuperUser
): ServerPathGroup(path) {

    companion object {
        var instance: OauthClientEndpoints? = null
    }
    init {
        instance = this
        prepareModels()
    }

    val modelInfo = modelInfoWithDefault(
        serialization = ModelSerializationInfo<OauthClient, String>(
            serializer = Serialization.module.serializer(),
            idSerializer = Serialization.module.serializer()
        ),
        authOptions = maintainPermissions as AuthOptions<HasId<*>>,
        getBaseCollection = { database().collection<OauthClient>() },
        exampleItem = { OauthClient(_id = "", scopes = setOf(), redirectUris = setOf(), niceName = "") },
        defaultItem = { OauthClient(_id = "", scopes = setOf(), redirectUris = setOf(), niceName = "") }
    )

    val rest = ModelRestEndpoints(path, modelInfo)
    val createSecret = path.arg<String>("_id").path("create-secret").post.api(
        authOptions = maintainPermissions,
        summary = "Create Secret",
        implementation = { _: Unit ->
            val newSecret = Base64.getEncoder().encodeToString(Random.nextBytes(24))
            modelInfo.collection().updateOneById(path1, modification {
                it.secrets += OauthClientSecret(masked = newSecret.take(3) + "*".repeat(newSecret.length-3), secretHash = newSecret.secureHash())
            }).new ?: NotFoundException()
            newSecret
        }
    )
}