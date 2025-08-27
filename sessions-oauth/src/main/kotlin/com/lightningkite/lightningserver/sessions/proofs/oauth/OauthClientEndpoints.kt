package com.lightningkite.lightningserver.sessions.proofs.oauth

import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.builder.bind
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.first
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.sessions.proofs.secureHash
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.ModelPermissions
import com.lightningkite.services.database.condition
import com.lightningkite.services.database.mask
import com.lightningkite.services.database.modification
import com.lightningkite.services.database.updateOneById
import java.util.*
import kotlin.random.Random

public class OauthClientEndpoints(
    private val database: Runtime<Database>,
    private val maintainPermissions: AuthRequirement<*, *> = AuthRequirement.IsSuperUser
): ServerBuilder() {


    public val modelInfo: ModelInfo<*, *, OauthClient, String> = database.modelInfo(
        authOptions = maintainPermissions or noAuth,
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

    public val rest: ModelRestEndpoints<out HasId<out Comparable<*>>?, out Comparable<*>, OauthClient, String> = ModelRestEndpoints(modelInfo)
    public val createSecret: Locationed<HttpEndpoint<PathSpec1<String>>, ApiHttpHandler<PathSpec1<String>, HasId<AnyId>, AnyId, Unit, String?>> =
        path.arg<String>("_id").path("create-secret").post bind ApiHttpHandler(
        authOptions = maintainPermissions,
        summary = "Create Secret",
        implementation = { _: Unit ->
            val newSecret = Base64.getEncoder().encodeToString(Random.nextBytes(24))
            modelInfo.collection().updateOneById(first, modification {
                it.secrets += OauthClientSecret(masked = newSecret.take(3) + "*".repeat(newSecret.length-3), secretHash = newSecret.secureHash(), createdAt = now())
            }).new ?: NotFoundException()
            newSecret
        }
    )
}