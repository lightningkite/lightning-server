package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.RequiredScope
import com.lightningkite.lightningserver.auth.Subscope
import com.lightningkite.lightningserver.definition.ListRegistryExtension
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.builder.ListRegistry
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.getValue
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.sessions.proofs.ProofMethod.Companion.baseScope
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.services.database.HasId
import kotlin.time.Duration.Companion.minutes


private object ProofMethods : ListRegistryExtension<ProofMethod>

public val ServerBuilder.proofMethods: ListRegistry<ProofMethod> by ProofMethods
public val ServerDefinition.proofMethods: List<ProofMethod> by ProofMethods
public val ServerRuntime.proofMethods: List<ProofMethod> get() = server.proofMethods

public interface ProofMethod {
    public val info: ProofMethodInfo

    public companion object {
        public val baseScope: RequiredScope = RequiredScope("auth:proofs")
    }

    context(server: ServerRuntime)
    public suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> established(
        principal: PrincipalType<SUBJECT, ID>,
        subject: SUBJECT,
    ): Boolean = info.property?.let { principal.getProperty(subject, it) != null } ?: false
}

public interface DirectProofMethod : ProofMethod {
    public val prove: ApiHttpHandler<PathSpec0, HasId<*>?, IdentificationAndPassword, Proof>
}

public interface StringProofMethod : ProofMethod {
    public val prove: ApiHttpHandler<PathSpec0, HasId<*>?, String, Proof>
}

public interface StartedProofMethod : ProofMethod {
    public val start: ApiHttpHandler<PathSpec0, HasId<*>?, String, String>
    public val prove: ApiHttpHandler<PathSpec0, HasId<*>?, FinishProof, Proof>
}

public interface ExternalProofMethod : ProofMethod {
    public val start: ApiHttpHandler<PathSpec0, HasId<*>?, String, String>
    public val indirectLink: PathSpec
}

public val ProofMethod.proofMethodAuth: AuthRequirement.Authenticated get() =
    AuthRequirement.Authenticated(
        scopes = setOf(baseScope.subscope(Subscope(info.via))),
        maxAge = 10.minutes
    )