package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.email.Email
import com.lightningkite.lightningserver.email.EmailClient
import com.lightningkite.lightningserver.email.EmailLabeledValue
import com.lightningkite.lightningserver.email.EmailPersonalization
import com.lightningkite.lightningserver.encryption.SecureHasher
import com.lightningkite.lightningserver.encryption.hasher
import com.lightningkite.lightningserver.encryption.secretBasis
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.sms.SMSClient
import com.lightningkite.lightningserver.typed.ApiEndpoint
import com.lightningkite.lightningserver.typed.ApiExample
import com.lightningkite.lightningserver.typed.typed
import kotlinx.datetime.Instant
import java.util.*
import com.lightningkite.UUID

class SmsProofEndpoints(
    path: ServerPath,
    pin: PinHandler,
    val sms: () -> SMSClient,
    val smsTemplate: suspend (pin: String) -> String = { code -> "Your ${generalSettings().projectName} code is ${code}. Don't share this with anyone." },
    proofHasher: () -> SecureHasher = secretBasis.hasher("proof"),
    val verifyPhone: suspend (String) -> Boolean = { true },
) : PinBasedProofEndpoints(path, "sms", "phone", proofHasher, pin) {
    init {
        path.docName = "SmsProof"
    }

    init {
        Authentication.register(this)
    }

    override val exampleTarget: String
        get() = "800-1000-100"

    override suspend fun send(to: String, pin: String) {
        if (verifyPhone(to))
            sms().send(to, smsTemplate(pin))
    }

    suspend fun send(destination: String, content: (Proof)->String) {
        sms().send(destination, content(issueProof(destination)))
    }
}