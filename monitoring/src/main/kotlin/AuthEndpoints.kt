package com.lightningkite.lightningserver.monitoring

import com.lightningkite.UUID
import com.lightningkite.lightningdb.condition
import com.lightningkite.lightningdb.eq
import com.lightningkite.lightningdb.findOne
import com.lightningkite.lightningdb.get
import com.lightningkite.lightningserver.auth.AuthType
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.auth.proof.EmailProofEndpoints
import com.lightningkite.lightningserver.auth.proof.KnownDeviceProofEndpoints
import com.lightningkite.lightningserver.auth.proof.OneTimePasswordProofEndpoints
import com.lightningkite.lightningserver.auth.proof.PasswordProofEndpoints
import com.lightningkite.lightningserver.auth.proof.PinHandler
import com.lightningkite.lightningserver.auth.proof.SmsProofEndpoints
import com.lightningkite.lightningserver.auth.subject.AuthEndpointsForSubject
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.email.Email
import com.lightningkite.lightningserver.email.EmailLabeledValue
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.toEmailAddress
import kotlinx.serialization.KSerializer

class AuthEndpoints(path: ServerPath): ServerPathGroup(path) {
    val pins = PinHandler(Server.cache, "pins")
    val proofPhone = SmsProofEndpoints(path("proof/phone"), pins, Server.sms)
    val proofEmail = EmailProofEndpoints(path("proof/email"), pins, Server.email, { to, pin ->
        Email(
            subject = "Log In Code",
            to = listOf(EmailLabeledValue(to)),
            plainText = "Your PIN is $pin."
        )
    })
    val proofOtp = OneTimePasswordProofEndpoints(path("proof/otp"), Server.database, Server.cache)
    val proofPassword = PasswordProofEndpoints(path("proof/password"), Server.database, Server.cache)
    val proofDevices = KnownDeviceProofEndpoints(path("proof/devices"), Server.database, Server.cache)
    val subjects = AuthEndpointsForSubject(
        path("subject"),
        object : Authentication.SubjectHandler<User, UUID> {
            override val name: String get() = "User"
            override val authType: AuthType get() = AuthType<User>()
            override val idSerializer: KSerializer<UUID>
                get() = Server.user.info.serialization.idSerializer
            override val subjectSerializer: KSerializer<User>
                get() = Server.user.info.serialization.serializer

            override suspend fun fetch(id: UUID): User =
                Server.user.info.collection().get(id) ?: throw NotFoundException()

            override suspend fun findUser(property: String, value: String): User? = when (property) {
                "email" -> Server.user.info.collection().findOne(condition { it.email eq value.toEmailAddress() })
                "_id" -> Server.user.info.collection().get(UUID.Companion.parse(value))
                else -> null
            }

            override val knownCacheTypes: List<RequestAuth.CacheKey<User, UUID, *>> = listOf(EmailCacheKey)

            override suspend fun desiredStrengthFor(result: User): Int = 5
        },
        database = Server.database
    )
}