package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.services.data.*
import com.lightningkite.services.email.Email
import com.lightningkite.services.email.EmailService
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Email-based authentication proof endpoint.
 * Allows users to prove ownership of an email address by receiving and entering a PIN code.
 *
 * Typical flow:
 * 1. Client calls beginEmailOwnershipProof with an email address
 * 2. Server sends a PIN code via email to that address
 * 3. Client receives the PIN from the user
 * 4. Client calls proveEmailOwnership with the temporary key and PIN
 * 5. Server validates the PIN and returns a signed Proof for authentication
 *
 * @param pin PIN handler for generating and validating PIN codes
 * @param email Email service for sending PIN codes
 * @param emailTemplate Template function to generate email content from recipient and PIN
 * @param proofSigner Signer for creating cryptographically signed proofs
 * @param screenEmail Optional function deciding whether to send to an address: [ScreeningResult.Allow] sends,
 *   [ScreeningResult.Suppress] silently sends nothing, and [ScreeningResult.Reject] returns an error to the client
 *   (e.g., domain restrictions or "Please use your work email address")
 *
 * Security considerations:
 * - PINs expire after a configurable duration (see PinHandler)
 * - Rate limiting should be applied to prevent abuse
 * - Email addresses are normalized to lowercase for consistency
 * - Use screenEmail to block disposable email providers if needed
 * - Prefer [ScreeningResult.Suppress] over [ScreeningResult.Reject] when rejecting would reveal whether an account exists
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

    /**
     * Send a PIN code to the specified email address.
     * Only called if [screen] returned [ScreeningResult.Allow] for the address.
     *
     * @param to Email address to send PIN to
     * @param pin PIN code to include in the email
     */
    context(_: ServerRuntime)
    override suspend fun send(to: String, pin: String) {
        email().send(emailTemplate(to.toEmailAddress(), pin))
    }

    /**
     * Advanced: Send a custom email with an embedded proof.
     * Useful for magic link authentication or custom email templates.
     *
     * The content function receives a signed Proof that can be embedded in the email
     * (e.g., as a magic link parameter). The proof's signature ensures the link can't be forged.
     *
     * @param destination Email address to send to
     * @param content Function that takes a Proof and returns an Email to send
     * @throws IllegalArgumentException if the returned Email's 'to' field doesn't match destination
     *
     * Security: The email address mismatch check prevents accidentally sending proofs to wrong addresses.
     */
    context(_: ServerRuntime)
    public suspend fun send(destination: String, content: (Proof) -> Email) {
        email().send(content(issueProof(destination)).also {
            if (it.to.singleOrNull()?.value?.equals(destination) != true) {
                throw IllegalArgumentException("Email mismatch")
            }
        })
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