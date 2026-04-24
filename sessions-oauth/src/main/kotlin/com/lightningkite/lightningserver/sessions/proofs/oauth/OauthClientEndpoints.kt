package com.lightningkite.lightningserver.sessions.proofs.oauth

import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.secureHash
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.database.*
import java.util.*
import kotlin.random.Random

public class OauthClientEndpoints(
    database: Runtime<Database>,
    private val maintainPermissions: AuthRequirement<*> = AuthRequirement.IsSuperUser,
) : ServerBuilder() {

    public val modelInfo: ModelInfo<HasId<*>?, OauthClient, String> = database.modelInfo(
        auth = maintainPermissions or noAuth,
        permissions = {
            val isRoot = maintainPermissions.accepts(authOrNull)
            ModelPermissions(
                read = condition(true),
                readMask = mask {
                    it.secrets.maskedTo(setOf()).unless(condition(isRoot))
                },
                manage = condition(isRoot)
            )
        },
    )

    public val rest: ModelRestEndpoints<HasId<*>?, OauthClient, String> = ModelRestEndpoints(modelInfo)
    public val createSecret: ApiHttpHandler<PathSpec1<String>, HasId<*>?, Unit, String?> =
        path.arg<String>("_id").path("create-secret").post bind ApiHttpHandler(
            auth = maintainPermissions,
            summary = "Create Secret",
            implementation = { _: Unit ->
                val newSecret = Base64.getEncoder().encodeToString(Random.nextBytes(24))
                modelInfo.table().updateOneById(request.arg1, modification {
                    it.secrets += OauthClientSecret(
                        masked = newSecret.take(3) + "*".repeat(newSecret.length - 3),
                        secretHash = newSecret.secureHash(),
                        createdAt = now()
                    )
                }).new ?: throw NotFoundException()
                newSecret
            }
        )
}