package com.lightningkite.lightningserver.sessions.proofs.oauth

import com.lightningkite.lightningserver.ExperimentalOauthProviderApi
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.fastHash
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.database.*
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlin.io.encoding.Base64

/**
 * The registry of [OauthClient]s, for maintainers only: REST at this module's path, and `{_id}/create-secret`.
 * Nothing else needs to read it; the consent screen gets the client's name from the provider.
 */
@ExperimentalOauthProviderApi
public class OauthClientEndpoints(
    database: Runtime<Database>,
    private val maintainPermissions: AuthRequirement<*> = AuthRequirement.IsSuperUser,
) : ServerBuilder() {

    public val modelInfo: ModelInfo<HasId<*>?, OauthClient, String> = database.modelInfo(
        auth = maintainPermissions,
        tableName = "OauthClient",
        permissions = {
            val maintainer: Condition<OauthClient> = Condition(maintainPermissions.accepts(authOrNull))
            ModelPermissions(read = maintainer, manage = maintainer)
        },
    )

    public val rest: ModelRestEndpoints<HasId<*>?, OauthClient, String> = path include ModelRestEndpoints(modelInfo)
    public val createSecret: ApiHttpHandler<PathSpec1<String>, HasId<*>?, Unit, String?> =
        path.arg<String>("_id").path("create-secret").post bind ApiHttpHandler(
            auth = maintainPermissions,
            summary = "Create Secret",
            implementation = { _: Unit ->
                val newSecret = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(CryptographyRandom.nextBytes(24))
                modelInfo.table().updateOneById(request.arg1, Modification {
                    it.secrets += OauthClientSecret(
                        masked = newSecret.take(3) + "*".repeat(newSecret.length - 3),
                        // Fast hash, as for session secrets: the secret is high-entropy, and a slow hash on every token request is a CPU sink.
                        secretHash = newSecret.fastHash(),
                        createdAt = now()
                    )
                }).new ?: throw NotFoundException()
                newSecret
            }
        )
}