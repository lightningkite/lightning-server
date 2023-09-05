package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningserver.auth.Authentication
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
import com.lightningkite.lightningserver.typed.typed
import java.time.Instant
import java.util.*

class EmailProofEndpoints(
    path: ServerPath,
    val proofHasher: () -> SecureHasher,
    val pin: PinHandler,
    val email: () -> EmailClient,
    val emailTemplate: Email
) : ServerPathGroup(path), Authentication.StartedProofMethod {

    override val name: String
        get() = "email"
    override val humanName: String
        get() = "Email"
    override val strength: Int
        get() = 10
    override val validates: String
        get() = "email"

    override val start: ApiEndpoint<String, String> = path("start").post.typed(
        summary = "Begin Email Ownership Proof",
        description = "Sends a login code to the given address.  The email will contain both a link to instantly log in and a PIN that can be combined with the returned key to log in.",
        errorCases = listOf(),
        examples = listOf(
            ApiExample(
                input = "test@test.com",
                output = "generated_opaque_identifier",
            ),
            ApiExample(
                input = "TeSt@tEsT.CoM ",
                output = "generated_opaque_identifier",
                name = "Casing doesn't matter",
                notes = "The casing of the email address is ignored, and the input is trimmed."
            ),
        ),
        successCode = HttpStatus.NoContent,
        implementation = { _: Unit, addressUnsafe: String ->
            val address = addressUnsafe.lowercase().trim()
            val p = pin.establish(address)
            email().send(EmailPersonalization(
                to = listOf(EmailLabeledValue(address)),
                substitutions = mapOf("{{PIN}}" to p.pin)
            )(emailTemplate))
            p.key
        }
    )
    override val prove: ApiEndpoint<ProofEvidence, Proof> = path("prove").post.typed(
        summary = "Prove $validates ownership",
        description = "Logs in to the given account with a PIN that was provided in an email sent earlier and the key from that request.  Note that the PIN expires in ${pin.expiration.toMinutes()} minutes, and you are only permitted ${pin.maxAttempts} attempts.",
        errorCases = listOf(),
        examples = listOf(
            ApiExample(
                input = ProofEvidence("test@test.com", pin.generate()),
                output = Proof(
                    via = name,
                    of = validates,
                    strength = strength,
                    value = "test@test.com",
                    at = Instant.now(),
                    signature = "opaquesignaturevalue"
                )
            )
        ),
        successCode = HttpStatus.OK,
        implementation = { _: Unit, input: ProofEvidence ->
            proofHasher().makeProof(
                info = info,
                value = pin.assert(input.value, input.secret),
                at = Instant.now()
            )
        }
    )
}