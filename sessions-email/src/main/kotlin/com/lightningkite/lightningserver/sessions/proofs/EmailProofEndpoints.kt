package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.sdk.SdkModule
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.lightningserver.typed.sdk.clientInterface
import com.lightningkite.lightningserver.typed.sdk.info
import com.lightningkite.lightningserver.typed.sdk.sdkSettings
import com.lightningkite.services.email.Email
import com.lightningkite.services.email.EmailService

public class EmailProofEndpoints(
    pin: PinHandler,
    private val email: Runtime<EmailService>,
    private val emailTemplate: suspend context(ServerRuntime) (String, String) -> Email,
    proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proof"),
    private val verifyEmail: suspend context(ServerRuntime) (String) -> Boolean = { true },
) : PinBasedProofEndpoints(
    name = "email",
    property = "email",
    proofSigner = proofSigner,
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

    context(_: ServerRuntime)
    override suspend fun send(to: String, pin: String) {
        if(verifyEmail(to))
            email().send(emailTemplate(to, pin))
    }

    context(_: ServerRuntime)
    public suspend fun send(destination: String, content: (Proof)->Email) {
        email().send(content(issueProof(destination)).also {
            if(it.to.singleOrNull()?.value?.equals(destination) != true) {
                throw IllegalArgumentException("Email mismatch")
            }
        })
    }
}