package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.LSError
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
import com.lightningkite.lightningserver.sessions.proofs.extensions.constrainAttemptRate
import com.lightningkite.lightningserver.sessions.proofs.extensions.makeProof
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.services.database.HasId
import kotlin.time.Duration

public abstract class PinBasedProofEndpoints(
    public val name: String,
    public val property: String,
    override val proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proof"),
    override val proofExpiration: Duration,
    public val pin: PinHandler,
    public val exampleTarget: String,
    public val strength: Int = 10,
) : ServerBuilder(), StartedProofMethod {

    init {
        proofMethodsRegistry.register(this)
    }

    public open fun normalize(to: String): String = to.lowercase().trim()

    context(_: ServerRuntime)
    protected abstract suspend fun send(to: String, pin: String)

    /**
     * The outcome of [screen], deciding how a request to start a proof is handled.
     */
    public sealed interface ScreeningResult {
        /** Send the PIN as normal. */
        public data object Allow : ScreeningResult

        /** Respond as if successful, but send nothing (prevents account enumeration). */
        public data object Suppress : ScreeningResult

        /** Reject the request with a 400 error that can be displayed to the user. */
        public data class Reject(val message: String, val detail: String = DEFAULT_DETAIL) : ScreeningResult {
            public companion object {
                public const val DEFAULT_DETAIL: String = "target-rejected"
            }
        }
    }

    /**
     * Decides whether a PIN should be sent to the given (already normalized) destination.
     * Called before a PIN is established, within the attempt rate limit.
     */
    context(_: ServerRuntime)
    public open suspend fun screen(to: String): ScreeningResult = ScreeningResult.Allow

    final override val info: ProofMethodInfo = ProofMethodInfo(
        via = name,
        property = property,
        strength = strength
    )

    public override val start: ApiHttpHandler<PathSpec0, HasId<*>?, String, String> =
        path.path("start").post bind ApiHttpHandler(
            auth = noAuth,
            summary = "Begin $name Ownership Proof",
            description = "Sends a login code to the given ${name.lowercase()}. The message will contain both a PIN that can be combined with the returned key to log in.",
            errorCases = listOf(
                LSError(
                    http = HttpStatus.BadRequest.code,
                    detail = ScreeningResult.Reject.DEFAULT_DETAIL,
                    message = "The given ${name.lowercase()} is not permitted."
                )
            ),
            successCode = HttpStatus.OK,
            implementation = { valueUnsafe: String ->
                val value = normalize(valueUnsafe)

                pin.cache().constrainAttemptRate(
                    cacheKey = "$name-pin-count-${value}"
                ) {
                    when (val screening = screen(value)) {
                        ScreeningResult.Allow -> pin.establish(value).also { send(value, it.pin) }.key
                        // Still establish a PIN so the key behaves identically to a real one.
                        ScreeningResult.Suppress -> pin.establish(value).key
                        is ScreeningResult.Reject -> throw BadRequestException(
                            detail = screening.detail,
                            message = screening.message
                        )
                    }
                }
            }
        )

    context(_: ServerRuntime)
    protected suspend fun issueProof(destination: String): Proof {
        return proofSigner.await().makeProof(
            property = property,
            value = destination,
        )
    }

    override val prove: ApiHttpHandler<PathSpec0, HasId<*>?, FinishProof, Proof> =
        path.path("prove").post bind ApiHttpHandler(
            auth = noAuth,
            summary = "Prove ${info.property} ownership",
            description = "Logs in to the given account with a PIN that was sent earlier and the key from that request. Note that the PIN expires in ${pin.expiration.inWholeMinutes} minutes, and you are only permitted ${pin.maxAttempts} attempts.",
            errorCases = emptyList(),
            successCode = HttpStatus.OK,
            implementation = { input: FinishProof ->
                proofSigner.await().makeProof(
                    property = property,
                    value = pin.assert(input.key, input.password),
                )
            }
        )

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> established(
        principal: PrincipalType<SUBJECT, ID>,
        subject: SUBJECT,
    ): Boolean = principal.getProperty(subject, property) != null
}