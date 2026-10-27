package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.data.Request
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
 * Provides endpoints for proving ownership of a phone number with a PIN or link sent by SMS.
 *
 * With a PIN, the client begins with a phone number, receives the PIN by SMS, and exchanges it with the temporary key for a signed [Proof]. Phone numbers are normalized by [normalize], and the method has a strength of 5. To send a link instead, call the second `send` overload, which embeds the [Proof] directly in the message.
 *
 * @param pin Generates and validates the PIN codes.
 * @param smsTemplate Builds the SMS text from the PIN.
 * @param screenPhone Decides whether to send to a number, such as to block premium rate or international numbers: [ScreeningResult.Allow] sends, [ScreeningResult.Suppress] silently sends nothing, and [ScreeningResult.Reject] returns an error to the client. Prefer [ScreeningResult.Suppress] when rejecting would reveal whether an account exists.
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
     * Returns [to] in E.164 format, such as `+15551234567`.
     *
     * This removes any extension after `x`, keeps only the digits, and prepends `+`. A 10-digit number is assumed to be from the US and gets the country code `1`, so `555.123.4567 x123` becomes `+15551234567`.
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

    /** Applies [screenPhone] to the normalized phone number. */
    context(_: ServerRuntime)
    public override suspend fun screen(to: String): ScreeningResult = screenPhone(to.toPhoneNumber())

    /** Sends [pin] by SMS to the phone number [to] using [smsTemplate]. Only called when [screen] allowed the number. */
    context(_: ServerRuntime)
    override suspend fun send(to: String, pin: String) {
        sms().send(to.toPhoneNumber(), smsTemplate(pin))
    }

    /**
     * Sends the SMS that [content] builds from a newly issued [Proof] for [destination], such as a login link.
     *
     * The proof is issued without anyone presenting a credential, so whoever receives it can authenticate. Pass the [request] that asked for it, if any, so its origin can be audited if needed. Unlike the PIN overload, this does not apply [screenPhone] or normalize [destination].
     */
    context(_: ServerRuntime)
    public suspend fun send(destination: PhoneNumber, request: Request<*>?, content: (Proof) -> String) {
        sms().send(destination, content(issueProof(destination.raw, request)))
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