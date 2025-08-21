package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.auth.AnyId
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.auth
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.encryption.SecureHasher
import com.lightningkite.lightningserver.encryption.hasher
import com.lightningkite.lightningserver.sessions.token.PrivateTinyTokenFormat
import com.lightningkite.lightningserver.sessions.token.TokenFormat
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.lightningserver.typed.modelInfo2
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.Uuid

public class SessionManager<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
    public val principal: PrincipalType<SUBJECT, ID>,
    public val database: Runtime<Database>,
    public val proofHasher: RuntimeDeferred<SecureHasher> = secretBasis.hasher("proof"),
    public val tokenFormat: Runtime<TokenFormat> = Runtime { PrivateTinyTokenFormat() }
) {
    public val sessionInfo: ModelInfo<SUBJECT, ID, Session<SUBJECT, ID>, Uuid> =
        database.modelInfo2(
            authOptions = principal.auth(scopes = setOf("sessions")),
            serializer = Session.serializer(principal.subjectSerializer, principal.idSerializer),
            idSerializer = Uuid.serializer(),
            collectionName = principal.name + "Session",
            permissions = {
                val requestAuth = this.authOrNull
                val canUse: Condition<Session<SUBJECT, ID>> = when {
                    requestAuth == null -> Condition.Never
                    else -> Condition.OnField(
                        Session_subjectId(principal.subjectSerializer, principal.idSerializer),
                        @Suppress("UNCHECKED_CAST")
                        Condition.Equal(requestAuth.rawId as ID)
                    )
                }
                val isRoot: Condition<Session<SUBJECT, ID>> =
                    if (Authentication.isSuperUser.accepts(requestAuth)) Condition.Always else Condition.Never
                collection.withPermissions(
                    permissions = ModelPermissions(
                        create = isRoot,
                        read = canUse,
                        readMask = Mask(
                            listOf(
                                Condition.Never to Modification.OnField(
                                    Session_secretHash(handler.subjectSerializer, handler.idSerializer),
                                    Modification.Assign("")
                                )
                            )
                        ),
                        update = isRoot,
                        delete = isRoot,
                    )
                )
            }
        )
}