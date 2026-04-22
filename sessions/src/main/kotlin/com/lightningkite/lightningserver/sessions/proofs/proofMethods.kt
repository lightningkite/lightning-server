package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.ListRegistry
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.sessions.proofs.ProofMethod.Companion.baseScope
import com.lightningkite.lightningserver.sessions.proofs.extensions.verify
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.services.database.HasId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes


private object ProofMethods : ListRegistryExtension<ProofMethod>

public val ServerBuilder.proofMethodsRegistry: ListRegistry<ProofMethod> by ProofMethods
public val ServerDefinition.proofMethods: List<ProofMethod> by ProofMethods
public val ServerRuntime.proofMethods: List<ProofMethod> get() = server.proofMethods

public interface ProofMethod {
    public val info: ProofMethodInfo

    public val proofSigner: RuntimeDeferred<Signer>
    public val proofExpiration: Duration

    public companion object {
        public val baseScope: RequiredScope = RequiredScope("auth:proofs")
    }

    /**
     * Checks if the given [proof] is valid and was issued by this [ProofMethod]
     * */
    context(server: ServerRuntime)
    public suspend fun isValid(proof: Proof): Boolean {
        // Backwards compatibility for expiresAt.
        val proof = if (proof.expiresAt != null) proof
        else proof.copy(expiresAt = proof.at + proofExpiration)
        return info.via == proof.via &&
                info.property?.let { proof.property == it } != false &&
                proof.expiresAt!! > now() &&
                proofSigner.await().verify(proof)
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
}

public val ProofMethod.proofMethodAuth: AuthRequirement.Authenticated
    get() = AuthRequirement.Authenticated(
        scopes = setOf(baseScope.subscope(Subscope(info.via))),
        maxAge = 10.minutes
    )