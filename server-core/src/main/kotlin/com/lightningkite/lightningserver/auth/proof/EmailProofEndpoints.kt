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
    proofHasher: () -> SecureHasher,
    pin: PinHandler,
    val email: () -> EmailClient,
    val emailTemplate: Email
) : PinBasedProofEndpoints(path, proofHasher, pin) {

    override val name: String
        get() = "email"
    override val humanName: String
        get() = "Email"
    override val strength: Int
        get() = 10
    override val validates: String
        get() = "email"
    override val exampleTarget: String
        get() = "test@test.com"

    override suspend fun send(to: String, pin: String) {
        email().send(EmailPersonalization(
            to = listOf(EmailLabeledValue(to)),
            substitutions = mapOf("{{PIN}}" to pin)
        )(emailTemplate))
    }
}