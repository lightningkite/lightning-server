package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.auth.AnyId
import com.lightningkite.lightningserver.auth.AuthAny
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.definition.ListRegistryExtension
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.builder.ListRegistry
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.getValue
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.services.database.HasId
import kotlin.time.Duration.Companion.minutes


private object ProofMethods : ListRegistryExtension<ProofMethod>

public val ServerBuilder.proofMethods: ListRegistry<ProofMethod> by ProofMethods
public val ServerDefinition.proofMethods: List<ProofMethod> by ProofMethods
public val ServerRuntime.proofMethods: List<ProofMethod> get() = server.proofMethods

public interface ProofMethod {
    public val info: ProofMethodInfo

    context(server: ServerRuntime)
    public suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> established(
        principal: PrincipalType<SUBJECT, ID>,
        item: SUBJECT,
    ): Boolean = info.property?.let { principal.getProperty(item, it) != null } ?: false
}

public interface DirectProofMethod : ProofMethod {
    public val prove: Locationed<HttpEndpoint<PathSpec0>, ApiHttpHandler<PathSpec0, HasId<AnyId>?, IdentificationAndPassword, Proof>>
}

public interface StringProofMethod : ProofMethod {
    public val prove: Locationed<HttpEndpoint<PathSpec0>, ApiHttpHandler<PathSpec0, HasId<AnyId>?, String, Proof>>
}

public interface StartedProofMethod : ProofMethod {
    public val start: Locationed<HttpEndpoint<PathSpec0>, ApiHttpHandler<PathSpec0, HasId<AnyId>?, String, String>>
    public val prove: Locationed<HttpEndpoint<PathSpec0>, ApiHttpHandler<PathSpec0, HasId<AnyId>?, FinishProof, Proof>>
}

public interface ExternalProofMethod : ProofMethod {
    public val start: Locationed<HttpEndpoint<PathSpec0>, ApiHttpHandler<PathSpec0, HasId<AnyId>?, String, String>>
    public val indirectLink: PathSpec
}

public val ProofMethod.proofMethodAuth: AuthRequirement.Authenticated get() =
    AuthRequirement.Authenticated(
        scopes = setOf("auth:proofs:${info.via}"),
        maxAge = 10.minutes
    )