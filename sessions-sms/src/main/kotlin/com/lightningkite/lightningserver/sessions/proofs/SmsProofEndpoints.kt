package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.services.data.PhoneNumber
import com.lightningkite.services.data.toPhoneNumber
import com.lightningkite.services.sms.SMS
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * SMS-based authentication proof endpoint.
 * Allows users to prove ownership of a phone number by receiving and entering a PIN code via SMS.
 *
 * Typical flow:
 * 1. Client calls beginSmsOwnershipProof with a phone number
 * 2. Server sends a PIN code via SMS to that number
 * 3. Client receives the PIN from the user
 * 4. Client calls provePhoneOwnership with the temporary key and PIN
 * 5. Server validates the PIN and returns a signed Proof for authentication
 *
 * @param pin PIN handler for generating and validating PIN codes
 * @param sms SMS service for sending PIN codes
 * @param smsTemplate Template function to generate SMS text from PIN code
 * @param proofSigner Signer for creating cryptographically signed proofs
 * @param screenPhone Optional function deciding whether to send to a number: [ScreeningResult.Allow] sends,
 *   [ScreeningResult.Suppress] silently sends nothing, and [ScreeningResult.Reject] returns an error to the client
 *   (e.g., country restrictions)
 *
 * Security considerations:
 * - PINs expire after a configurable duration (see PinHandler)
 * - SMS has higher strength (5) than email due to stricter verification
 * - Rate limiting should be applied to prevent SMS bombing
 * - Phone numbers are normalized to E.164 format (+[country][number])
 * - 10-digit US numbers are automatically prefixed with +1
 * - Use screenPhone to block premium rate or international numbers if needed
 * - Prefer [ScreeningResult.Suppress] over [ScreeningResult.Reject] when rejecting would reveal whether an account exists
 *
 * Cost considerations:
 * - SMS messages have per-message costs
 * - Implement rate limiting to prevent abuse and unexpected bills
 * - Consider blocking known VOIP and virtual phone number providers
 */
public class SmsProofEndpoints(
    pin: PinHandler,
    private val sms: Runtime<SMS>,
    private val smsTemplate: suspend context(ServerRuntime) (pin: String) -> String = { code -> "Your ${generalSettings().projectName} code is ${code}. Don't share this with anyone." },
    proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proof"),
    proofExpiration: Duration = 1.hours,
    private val screenPhone: suspend context(ServerRuntime) (PhoneNumber) -> ScreeningResult = { ScreeningResult.Allow },
) : PinBasedProofEndpoints(
    name = "sms",
    property = "phone",
    proofSigner = proofSigner,
    proofExpiration = proofExpiration,
    pin = pin,
    exampleTarget = "800-1000-100",
    strength = 5,
) {
    init {
        sdkSettings.defaultInfo = SdkModule.Info(
            interfaceName = "SmsProof",
            valueName = "sms"
        )
        sdkSettings.clientInterface = ProofClientEndpoints.Sms::class.info()
    }

    /**
     * Normalize phone numbers to E.164 format for consistent storage and comparison.
     *
     * Normalization rules:
     * - Removes leading + if present
     * - Removes extension (anything after 'x')
     * - Keeps only digits
     * - Adds + prefix
     * - For 10-digit numbers (US), prepends country code 1
     *
     * Examples:
     * - "+1-555-123-4567" → "+15551234567"
     * - "555-123-4567" → "+15551234567"
     * - "555.123.4567 x123" → "+15551234567"
     * - "+44 20 7946 0958" → "+442079460958"
     *
     * @param to Phone number in various formats
     * @return E.164 formatted phone number
     */
    override fun normalize(to: String): String = to
        .removePrefix("+")
        .substringBefore('x')
        .filter { it.isDigit() }
        .let {
            "+" + when (it.length) {
                10 -> "1$it"
                else -> it
            }
        }

    /**
     * Applies [screenPhone] to the normalized phone number.
     */
    context(_: ServerRuntime)
    public override suspend fun screen(to: String): ScreeningResult = screenPhone(to.toPhoneNumber())

    /**
     * Send a PIN code to the specified phone number via SMS.
     * Only called if [screen] returned [ScreeningResult.Allow] for the number.
     *
     * @param to Phone number to send PIN to (will be normalized)
     * @param pin PIN code to include in the SMS
     */
    context(_: ServerRuntime)
    override suspend fun send(to: String, pin: String) {
        sms().send(to.toPhoneNumber(), smsTemplate(pin))
    }

    /**
     * Advanced: Send a custom SMS with an embedded proof.
     * Useful for custom SMS templates or workflows.
     *
     * The content function receives a signed Proof that can be embedded in the SMS.
     * The proof's signature ensures it can't be forged.
     *
     * @param destination Phone number to send to (will be normalized)
     * @param content Function that takes a Proof and returns SMS text to send
     *
     * Security: The proof is signed and time-limited to prevent tampering or replay.
     */
    context(_: ServerRuntime)
    public suspend fun send(destination: String, content: (Proof) -> String) {
        sms().send(destination.toPhoneNumber(), content(issueProof(destination)))
    }
}

@Deprecated("Use the \"screenPhone\" parameter instead of \"verifyPhone\".")
public fun SmsProofEndpoints(
    pin: PinHandler,
    sms: Runtime<SMS>,
    smsTemplate: suspend context(ServerRuntime) (pin: String) -> String = { code -> "Your ${generalSettings().projectName} code is ${code}. Don't share this with anyone." },
    proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proof"),
    proofExpiration: Duration = 1.hours,
    verifyPhone: suspend context(ServerRuntime) (String) -> Boolean,
): SmsProofEndpoints = SmsProofEndpoints(
    pin = pin,
    sms = sms,
    smsTemplate = smsTemplate,
    proofSigner = proofSigner,
    proofExpiration = proofExpiration,
    screenPhone = { phone: PhoneNumber ->
        if (verifyPhone(phone.raw)) PinBasedProofEndpoints.ScreeningResult.Allow
        else PinBasedProofEndpoints.ScreeningResult.Suppress
    }
)

/*
 * TODO API Recommendations:
 *
 * 1. Phone number normalization could be improved:
 *    - Add support for more international formats
 *    - Consider using a library like libphonenumber for robust parsing
 *    - Add validation to reject clearly invalid numbers before sending
 *
 * 2. Consider adding SMS provider detection to block:
 *    - VOIP numbers (Google Voice, Skype, etc.)
 *    - Temporary/disposable phone numbers
 *    - Premium rate numbers
 *
 * 3. Add built-in rate limiting per phone number to prevent:
 *    - SMS bombing attacks
 *    - Unexpected cost spikes
 *    - Suggested: 3 SMS per phone per hour, 10 per day
 *
 * 4. Consider adding message length validation:
 *    - SMS messages over 160 characters may be split/charged multiple times
 *    - Warn or prevent overly long templates
 *
 * 5. Add support for internationalization of SMS templates based on phone country code.
 *
 * 6. Consider adding delivery receipt tracking to detect failed deliveries.
 */