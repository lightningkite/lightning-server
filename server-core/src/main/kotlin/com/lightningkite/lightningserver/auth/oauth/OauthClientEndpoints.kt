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
): ServerPathGroup(path) {

    companion object {
        var instance: OauthClientEndpoints? = null
    }
    init {
        instance = this
        prepareModels()
    }

    @Suppress("UNCHECKED_CAST")
    val modelInfo = object: ModelInfoWithDefault<HasId<*>, OauthClient, String> {
        override val serialization = ModelSerializationInfo<OauthClient, String>(
            serializer = Serialization.module.serializer(),
            idSerializer = Serialization.module.serializer()
        )
        override val authOptions: AuthOptions<HasId<*>> get() = Authentication.isSuperUser as AuthOptions<HasId<*>>
        override fun collection(): FieldCollection<OauthClient> = database().collection<OauthClient>()

        override suspend fun collection(auth: AuthAccessor<HasId<*>>): FieldCollection<OauthClient> = collection()

        override suspend fun defaultItem(auth: RequestAuth<HasId<*>>?): OauthClient {
            return OauthClient(_id = "", scopes = setOf(), redirectUris = setOf(), niceName = "")
        }

        override fun exampleItem(): OauthClient? {
            return OauthClient(_id = "", scopes = setOf(), redirectUris = setOf(), niceName = "")
        }
    }

    val rest = ModelRestEndpoints(path, modelInfo)
    val createSecret = path.arg<String>("_id").path("create-secret").post.api(
        authOptions = Authentication.isSuperUser,
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