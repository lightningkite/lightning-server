package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.sdk.SdkModule
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.lightningserver.typed.sdk.clientInterface
import com.lightningkite.lightningserver.typed.sdk.info
import com.lightningkite.lightningserver.typed.sdk.sdkSettings
import com.lightningkite.services.sms.SMS
import com.lightningkite.toPhoneNumber

public class SmsProofEndpoints(
    pin: PinHandler,
    private val sms: Runtime<SMS>,
    private val smsTemplate: suspend context(ServerRuntime) (pin: String) -> String = { code -> "Your ${generalSettings().projectName} code is ${code}. Don't share this with anyone." },
    proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proof"),
    private val verifyPhone: suspend context(ServerRuntime) (String) -> Boolean = { true },
) : PinBasedProofEndpoints(
    name = "sms",
    property = "phone",
    proofSigner = proofSigner,
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

    context(_: ServerRuntime)
    override suspend fun send(to: String, pin: String) {
        if (verifyPhone(to))
            sms().send(to.toPhoneNumber(), smsTemplate(pin))
    }

    context(_: ServerRuntime)
    public suspend fun send(destination: String, content: (Proof) -> String) {
        sms().send(destination.toPhoneNumber(), content(issueProof(destination)))
    }
}