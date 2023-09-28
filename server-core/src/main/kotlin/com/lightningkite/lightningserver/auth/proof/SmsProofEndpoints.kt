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
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.sms.SMSClient
import com.lightningkite.lightningserver.typed.ApiEndpoint
import com.lightningkite.lightningserver.typed.ApiExample
import com.lightningkite.lightningserver.typed.typed
import kotlinx.datetime.Instant
import java.util.*

class SmsProofEndpoints(
    path: ServerPath,
    proofHasher: () -> SecureHasher,
    pin: PinHandler,
    val sms: () -> SMSClient,
    val smsTemplate: (pin: String) -> String = { code -> "Your ${generalSettings().projectName} code is ${code}. Don't share this with anyone." }
) : PinBasedProofEndpoints(path, proofHasher, pin) {
    init {
        if(path.docName == null) path.docName = "SmsProof"
    }

    override val name: String
        get() = "phone"
    override val humanName: String
        get() = "Phone"
    override val strength: Int
        get() = 10
    override val validates: String
        get() = "phone"
    override val exampleTarget: String
        get() = "800-1000-100"

    override suspend fun send(to: String, pin: String) {
        sms().send(to, smsTemplate(pin))
    }
}