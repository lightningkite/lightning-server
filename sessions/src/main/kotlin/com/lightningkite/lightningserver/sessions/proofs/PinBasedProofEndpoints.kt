package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.sessions.reportAuthEvent
import com.lightningkite.lightningserver.sessions.AuthEvent
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.NotFoundException
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

    context(server: ServerRuntime)
    protected suspend fun issueProof(destination: String, request: Request<*>?): Proof {
        val proof = proofSigner.await().makeProof(
            property = property,
            value = destination,
        )
        // Recorded here rather than at the call sites that mail it: every path that mints an
        // unpresented proof goes through this function, so instrumenting it is what makes the record
        // complete. The proof is a bearer credential the moment it exists, so the mint is the event.
        //
        // `request` is the request that asked for the link to be sent, where the caller has one —
        // which is the normal case, since a frontend triggers this. It carries the IP and user agent
        // of whoever asked, which is what an investigation into a misused link starts from.
        server.reportAuthEvent(AuthEvent.ProofIssued(info, principal = destination, request = request))
        return proof
    }

    override val prove: ApiHttpHandler<PathSpec0, HasId<*>?, FinishProof, Proof> =
        path.path("prove").post bind ApiHttpHandler(
            auth = noAuth,
            summary = "Prove ${info.property} ownership",
            description = "Logs in to the given account with a PIN that was sent earlier and the key from that request. Note that the PIN expires in ${pin.expiration.inWholeMinutes} minutes, and you are only permitted ${pin.maxAttempts} attempts.",
            errorCases = emptyList(),
            successCode = HttpStatus.OK,
            implementation = { input: FinishProof ->
                // What the attempt is against. A PIN proves ownership of an address, not of an account —
                // these endpoints never resolve a subject, that happens later at login — so the address is
                // the truthful answer to "who was this about", and it is the only one a failure has.
                val target = pin.pendingTarget(input.key)
                val value = try {
                    pin.assert(input.key, input.password)
                } catch (e: NotFoundException) {
                    serverRuntime.reportAuthEvent(
                        if (target == null) AuthEvent.ProofRejected(info, AuthEvent.ProofRejected.Reason.NoSuchChallenge, request)
                        else AuthEvent.ProofCredentialRejected(info, AuthEvent.ProofCredentialRejected.Reason.SecretExpired, target, request)
                    )
                    throw e
                } catch (e: BadRequestException) {
                    serverRuntime.reportAuthEvent(
                        if (target == null) AuthEvent.ProofRejected(info, AuthEvent.ProofRejected.Reason.NoSuchChallenge, request)
                        else AuthEvent.ProofCredentialRejected(info, AuthEvent.ProofCredentialRejected.Reason.SecretMismatch, target, request)
                    )
                    throw e
                }
                serverRuntime.reportAuthEvent(AuthEvent.ProofAccepted(info, principal = value, request = request))
                proofSigner.await().makeProof(
                    property = property,
                    value = value,
                )
            }
        )

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> established(
        principal: PrincipalType<SUBJECT, ID>,
        subject: SUBJECT,
    ): Boolean = principal.getProperty(subject, property) != null
}