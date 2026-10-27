package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.services.data.*
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
 * @param screenEmail Decides whether to send to an address: [ScreeningResult.Allow] sends, [ScreeningResult.Suppress] silently sends nothing, and [ScreeningResult.Reject] returns an error to the client. Prefer [ScreeningResult.Suppress] when rejecting would reveal whether an account exists.
 */
public class EmailProofEndpoints(
    pin: PinHandler,
    private val email: Runtime<EmailService>,
    private val emailTemplate: suspend context(ServerRuntime) (EmailAddress, String) -> Email,
    proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proof"),
    proofExpiration: Duration = 1.hours,
    private val screenEmail: suspend context(ServerRuntime) (EmailAddress) -> ScreeningResult = { ScreeningResult.Allow },
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

    /**
     * Validates the address and applies [screenEmail].
     *
     * @throws BadRequestException if [to] is not a valid email address
     */
    context(_: ServerRuntime)
    public override suspend fun screen(to: String): ScreeningResult {
        val address =
            to.toEmailAddressOrNull() ?: throw BadRequestException(message = "$to is not a valid email address")
        return screenEmail(address)
    }

    /** Sends [pin] to the email address [to] using [emailTemplate]. Only called when [screen] allowed the address. */
    context(_: ServerRuntime)
    override suspend fun send(to: String, pin: String) {
        email().send(emailTemplate(to.toEmailAddress(), pin))
    }

    /**
     * Sends the email that [content] builds from a newly issued [Proof] for [destination], such as a magic link.
     *
     * The proof is issued without anyone presenting a credential, so whoever receives it can authenticate. Pass the [request] that asked for it, if any, so its origin is recorded in the audit trail. Unlike the PIN overload, this does not apply [screenEmail].
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

@Deprecated("Use the \"screenEmail\" parameter instead of \"verifyEmail\".")
public fun EmailProofEndpoints(
    pin: PinHandler,
    email: Runtime<EmailService>,
    emailTemplate: suspend context(ServerRuntime) (String, String) -> Email,
    proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proof"),
    proofExpiration: Duration = 1.hours,
    verifyEmail: suspend context(ServerRuntime) (String) -> Boolean,
): EmailProofEndpoints = EmailProofEndpoints(
    pin = pin,
    email = email,
    emailTemplate = { to: EmailAddress, pin: String -> emailTemplate(to.raw, pin) },
    proofSigner = proofSigner,
    proofExpiration = proofExpiration,
    screenEmail = { address: EmailAddress ->
        if (verifyEmail(address.raw)) PinBasedProofEndpoints.ScreeningResult.Allow
        else PinBasedProofEndpoints.ScreeningResult.Suppress
    }
)

/*
 * TODO API Recommendations:
 *
 * 1. Consider adding a built-in magic link template as an alternative to PIN codes for better UX.
 *
 * 2. Consider adding email normalization beyond lowercase (e.g., Gmail dot/plus address handling).
 *
 * 3. Consider adding support for HTML email templates in addition to the current plain text support.
 *
 * 4. Consider adding a rate limiter parameter to prevent email bombing attacks.
 */