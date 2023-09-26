@file:UseContextualSerialization(UUID::class)

package com.lightningkite.lightningserver.auth.subject.test

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.auth.AuthType
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.auth.authOptions
import com.lightningkite.lightningserver.auth.proof.*
import com.lightningkite.lightningserver.auth.subject.AuthEndpointsForSubject
import com.lightningkite.lightningserver.auth.token.TinyTokenFormat
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.ModelSerializationInfo
import com.lightningkite.lightningserver.db.modelInfoWithDefault
import com.lightningkite.lightningserver.email.Email
import com.lightningkite.lightningserver.email.TestEmailClient
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.typed.AuthAndPathParts
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import org.junit.Assert.*
import org.junit.Test
import java.util.*

class AuthEndpointsForSubjectTest {
    init {
        prepareModels()
    }

    val userInfo = modelInfoWithDefault<TestUser, TestUser, UUID>(
        getCollection = {
            TestSettings.database().collection<TestUser>()
        },
        defaultItem = { TestUser(email = "") },
        forUser = { it },
        authOptions = authOptions<TestUser>(),
        serialization = ModelSerializationInfo()
    )
    val subject = object : Authentication.SubjectHandler<TestUser, UUID> {
        override val name: String get() = "User"
        override val idProofs: Set<String> = setOf("email")
        override val authType: AuthType get() = AuthType<TestUser>()
        override val applicableProofs: Set<String> = setOf("email", "otp")
        override suspend fun authenticate(vararg proofs: Proof): Authentication.AuthenticateResult<TestUser, UUID>? {
            val emailIdentifier = proofs.find { it.of == "email" } ?: return null
            val user = userInfo.collection().findOne(condition { it.email eq emailIdentifier.value }) ?: run {
                userInfo.collection().insertOne(TestUser(
                    email = emailIdentifier.value
                ))
            } ?: return null
            val options = listOfNotNull(
                ProofOption(proofEmail.info, user.email),
//                proofOtp.proofOption(this, user._id),
            )
            return Authentication.AuthenticateResult(
                id = user._id,
                subjectCopy = user,
                options = options,
                strengthRequired = 15
            )
        }

        override val idSerializer: KSerializer<UUID>
            get() = userInfo.serialization.idSerializer
        override val subjectSerializer: KSerializer<TestUser>
            get() = userInfo.serialization.serializer

        override suspend fun fetch(id: UUID): TestUser = userInfo.collection().get(id) ?: throw NotFoundException()
        override val knownCacheTypes: List<RequestAuth.CacheKey<TestUser, UUID, *>> = listOf(EmailCacheKey)
    }

    val proofEmail = EmailProofEndpoints(
        ServerPath(UUID.randomUUID().toString()),
        TestSettings.jwtSigner,
        PinHandler(TestSettings.cache, "pin"),
        TestSettings.email,
        Email(
            subject = "Log In Code",
            to = listOf(),
            plainText = "Your PIN is {{PIN}}."
        )
    )
//    val proofOtp = OneTimePasswordProofEndpoints(
//        ServerPath(UUID.randomUUID().toString()),
//        TestSettings.jwtSigner,
//        TestSettings.database,
//        TestSettings.cache
//    )
    val endpoints = AuthEndpointsForSubject(
        path = ServerPath(UUID.randomUUID().toString()),
        handler = subject,
        database = TestSettings.database,
        proofHasher = TestSettings.jwtSigner,
        tokenFormat = { TinyTokenFormat(TestSettings.jwtSigner) }
    )

    object EmailCacheKey: RequestAuth.CacheKey<TestUser, UUID, String> {
        override val name: String
            get() = "email"
        override fun deserialize(string: String): String = string
        override fun serialize(value: String): String = value
        override suspend fun calculate(auth: RequestAuth<TestUser>): String = auth.get().email
    }

    @Test
    fun test(): Unit = runBlocking {
        val info = proofEmail.start.implementation(AuthAndPathParts(null, null, arrayOf()), "test@test.com")
        val pinRegex = Regex("[0-9][0-9][0-9][0-9][0-9][0-9]")
        val pin = (TestSettings.email() as TestEmailClient).lastEmailSent?.plainText?.let {
            pinRegex.find(it)?.value
        }!!
        val proof1 = proofEmail.prove.implementation(AuthAndPathParts(null, null, arrayOf()), ProofEvidence(
            value = info,
            secret = pin
        ))
        val result = endpoints.login.implementation(AuthAndPathParts(null, null, arrayOf()), listOf(proof1))
        println(result)
        assert(result.session != null)
    }
}

@GenerateDataClassPaths
@Serializable
data class TestUser(override val _id: UUID = UUID.randomUUID(), override val email: String) : HasId<UUID>, HasEmail