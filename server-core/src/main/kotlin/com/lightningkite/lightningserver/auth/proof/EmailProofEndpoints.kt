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
import com.lightningkite.lightningserver.encryption.hasher
import com.lightningkite.lightningserver.encryption.secretBasis
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.typed.ApiEndpoint
import com.lightningkite.lightningserver.typed.ApiExample
import com.lightningkite.lightningserver.typed.typed
import kotlinx.datetime.Instant
import java.util.*

class EmailProofEndpoints(
    path: ServerPath,
    pin: PinHandler,
    val email: () -> EmailClient,
    val emailTemplate: suspend (String, String) -> Email,
    proofHasher: () -> SecureHasher = secretBasis.hasher("proof"),
    val verifyEmail: suspend (String) -> Boolean = { true },
) : PinBasedProofEndpoints(path, proofHasher, pin) {
    init {
        if (path.docName == null) path.docName = "EmailProof"
    }

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
        if(verifyEmail(to))
            email().send(emailTemplate(to, pin))
    }

    suspend fun send(destination: String, content: (Proof)->Email) {
        email().send(content(issueProof(destination)).also {
            if(it.to.singleOrNull()?.value?.equals(destination, true) != true) {
                throw IllegalArgumentException("Email mismatch")
            }
        })
    }
}