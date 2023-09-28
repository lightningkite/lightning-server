package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.email.Email
import com.lightningkite.lightningserver.email.EmailClient
import com.lightningkite.lightningserver.email.EmailLabeledValue
import com.lightningkite.lightningserver.email.EmailPersonalization
import com.lightningkite.lightningserver.encryption.SecureHasher
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.typed.ApiEndpoint
import com.lightningkite.lightningserver.typed.ApiExample
import com.lightningkite.lightningserver.typed.api
import com.lightningkite.lightningserver.typed.typed
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.*

abstract class PinBasedProofEndpoints(
    path: ServerPath,
    val proofHasher: () -> SecureHasher,
    val pin: PinHandler,
) : ServerPathGroup(path), Authentication.StartedProofMethod {

    override val strength: Int
        get() = 10

    open fun normalize(to: String): String = to.lowercase().trim()
    abstract suspend fun send(to: String, pin: String)
    abstract val exampleTarget: String

    override val start = path("start").post.api(
        authOptions = noAuth,
        summary = "Begin $name Ownership Proof",
        description = "Sends a login code to the given ${name.lowercase()}.  The message will contain both a PIN that can be combined with the returned key to log in.",
        errorCases = listOf(),
        examples = listOf(
            ApiExample(
                input = exampleTarget,
                output = "generated_opaque_identifier",
            ),
            ApiExample(
                input = exampleTarget.uppercase(),
                output = "generated_opaque_identifier",
                name = "Casing doesn't matter",
                notes = "The casing of the target is ignored, and the input is trimmed."
            ),
        ),
        successCode = HttpStatus.OK,
        implementation = { addressUnsafe: String ->
            val address = normalize(addressUnsafe)
            val p = pin.establish(address)
            send(address, p.pin)
            p.key
        }
    )
    override val prove = path("prove").post.api(
        authOptions = noAuth,
        summary = "Prove $validates ownership",
        description = "Logs in to the given account with a PIN that was sent earlier and the key from that request.  Note that the PIN expires in ${pin.expiration.inWholeMinutes} minutes, and you are only permitted ${pin.maxAttempts} attempts.",
        errorCases = listOf(),
        examples = listOf(
            ApiExample(
                input = ProofEvidence(exampleTarget, pin.generate()),
                output = Proof(
                    via = name,
                    of = validates,
                    strength = strength,
                    value = exampleTarget,
                    at = Clock.System.now(),
                    signature = "opaquesignaturevalue"
                )
            )
        ),
        successCode = HttpStatus.OK,
        implementation = { input: ProofEvidence ->
            proofHasher().makeProof(
                info = info,
                value = pin.assert(input.value, input.secret),
                at = Clock.System.now()
            )
        }
    )
}