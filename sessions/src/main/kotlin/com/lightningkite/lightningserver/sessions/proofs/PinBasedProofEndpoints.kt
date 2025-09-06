package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.auth.AnyId
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.sessions.proofs.extensions.constrainAttemptRate
import com.lightningkite.lightningserver.sessions.proofs.extensions.makeProof
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.Documentable
import com.lightningkite.services.database.HasId

public abstract class PinBasedProofEndpoints(
    public val name: String,
    public val property: String,
    public val proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proof"),
    public val pin: PinHandler,
    public val exampleTarget: String,
    public val interfaceInfo: Documentable.OldInterfaceInfo,
    public val strength: Int = 10,
) : ServerBuilder(), StartedProofMethod {

    init {
        proofMethods.register(this)
    }

    public open fun normalize(to: String): String = to.lowercase().trim()

    context(_: ServerRuntime)
    protected abstract suspend fun send(to: String, pin: String)

    final override val info: ProofMethodInfo = ProofMethodInfo(
        via = name,
        property = property,
        strength = strength
    )

    public override val start: ApiHttpHandler<PathSpec0, HasId<AnyId>?, String, String> =
        path.path("start").post bind ApiHttpHandler(
            auth = noAuth,
            belongsToInterface = interfaceInfo,
            summary = "Begin $name Ownership Proof",
            description = "Sends a login code to the given ${name.lowercase()}.  The message will contain both a PIN that can be combined with the returned key to log in.",
            errorCases = emptyList(),
            successCode = HttpStatus.OK,
            implementation = { valueUnsafe: String ->
                val value = normalize(valueUnsafe)

                pin.cache().constrainAttemptRate(
                    cacheKey = "$name-pin-count-${value}"
                ) {
                    val p = pin.establish(value)
                    send(value, p.pin)
                    p.key
                }
            }
        )

    context(_: ServerRuntime)
    protected suspend fun issueProof(destination: String): Proof {
        return proofSigner.await().makeProof(
            info = info,
            property = info.property!!,
            value = destination,
            at = now()
        )
    }

    override val prove: ApiHttpHandler<PathSpec0, HasId<AnyId>?, FinishProof, Proof> =
        path.path("prove").post bind ApiHttpHandler(
            auth = noAuth,
            belongsToInterface = interfaceInfo,
            summary = "Prove ${info.property} ownership",
            description = "Logs in to the given account with a PIN that was sent earlier and the key from that request.  Note that the PIN expires in ${pin.expiration.inWholeMinutes} minutes, and you are only permitted ${pin.maxAttempts} attempts.",
            errorCases = emptyList(),
            successCode = HttpStatus.OK,
            implementation = { input: FinishProof ->
                proofSigner.await().makeProof(
                    info = info,
                    property = info.property!!,
                    value = pin.assert(input.key, input.password),
                    at = now()
                )
            }
        )

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> established(
        principal: PrincipalType<SUBJECT, ID>,
        item: SUBJECT,
    ): Boolean = principal.getProperty(item, property) != null
}