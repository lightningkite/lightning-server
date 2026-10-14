package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.services.email.Email
import com.lightningkite.services.email.EmailAddressWithName
import com.lightningkite.services.email.EmailService
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Provides endpoints for proving ownership of an email address with a PIN or magic link sent to it.
 *
 * With a PIN, the client begins with an email address, receives the PIN by email, and exchanges it with the temporary key for a signed [Proof]. Addresses are normalized to lowercase. To send a magic link instead, call the second `send` overload, which embeds the [Proof] directly in an email.
 *
 * @param pin Generates and validates the PIN codes.
 * @param emailTemplate Builds the email content from the recipient and PIN.
 * @param verifyEmail Returns whether an address is allowed for PINs, such as to block disposable email providers.
 */
public class EmailProofEndpoints(
    pin: PinHandler,
    private val email: Runtime<EmailService>,
    private val emailTemplate: suspend context(ServerRuntime) (String, String) -> Email,
    proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proof"),
    proofExpiration: Duration = 1.hours,
    private val verifyEmail: suspend context(ServerRuntime) (String) -> Boolean = { true },
) : PinBasedProofEndpoints(
    name = "email",
    property = "email",
    proofSigner = proofSigner,
    proofExpiration = proofExpiration,
    pin = pin,
    exampleTarget = "test@test.com"
) {
    init {
        sdkSettings.defaultInfo = SdkModule.Info(
            interfaceName = "EmailProof",
            valueName = "email"
        )
        sdkSettings.clientInterface = ProofClientEndpoints.Email::class.info()
    }

    /** Sends [pin] to the email address [to] using [emailTemplate], or sends nothing if [verifyEmail] rejects it. */
    context(_: ServerRuntime)
    override suspend fun send(to: String, pin: String) {
        if (verifyEmail(to))
            email().send(emailTemplate(to, pin))
    }

    /**
     * Sends the email that [content] builds from a newly issued [Proof] for [destination], such as a magic link.
     *
     * The proof is issued without anyone presenting a credential, so whoever receives it can authenticate. Pass the [request] that asked for it, if any, so its origin is recorded in the audit trail. Unlike the PIN overload, this does not apply [verifyEmail].
     *
     * @throws IllegalArgumentException if the built email is not addressed solely to [destination].
     */
    context(_: ServerRuntime)
    public suspend fun send(destination: EmailAddressWithName, request: Request<*>?, content: (Proof) -> Email) {
        val email = content(issueProof(destination.value.raw, request))
        if (email.to.singleOrNull() != destination) {
            throw IllegalArgumentException("Email mismatch")
        }
        email().send(email)
    }
}

/*
 * TODO API Recommendations:
 *
 * 1. Consider adding a built-in magic link template as an alternative to PIN codes for better UX.
 *
 * 2. Consider adding email normalization beyond lowercase (e.g., Gmail dot/plus address handling).
 *
 * 3. The verifyEmail function could be enhanced to return an error message that can be displayed
 *    to the user (e.g., "Please use your work email address").
 *
 * 4. Consider adding support for HTML email templates in addition to the current plain text support.
 *
 * 5. Consider adding a rate limiter parameter to prevent email bombing attacks.
 */