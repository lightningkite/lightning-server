@file:UseContextualSerialization(UUID::class)

package com.lightningkite.lightningserver.auth.subject.test

import com.lightningkite.default
import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.auth.authAny
import com.lightningkite.lightningserver.auth.oauth.OauthAccessType
import com.lightningkite.lightningserver.auth.oauth.OauthClient
import com.lightningkite.lightningserver.auth.oauth.OauthCodeRequest
import com.lightningkite.lightningserver.auth.oauth.OauthTokenRequest
import com.lightningkite.lightningserver.auth.proof.*
import com.lightningkite.lightningserver.email.TestEmailClient
import com.lightningkite.lightningserver.exceptions.HttpStatusException
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.sms.TestSMSClient
import com.lightningkite.lightningserver.testmodels.phoneNumber
import com.lightningkite.lightningserver.typed.AuthAndPathParts
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.uuid
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.UseContextualSerialization
import org.junit.Test
import java.util.*
import com.lightningkite.UUID
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class AuthEndpointsForSubjectTest {

    @Test
    fun test(): Unit = runBlocking {
        val info = TestSettings.proofEmail.start.test(null, "test@test.com")
        val pinRegex = Regex("[A-Z][A-Z][A-Z][A-Z][A-Z][A-Z]")
        val pin = (TestSettings.email() as TestEmailClient).lastEmailSent?.also { println(it) }?.plainText?.let {
            pinRegex.find(it)?.value
        }!!
        val proof1 = TestSettings.proofEmail.prove.test(null, FinishProof(
            key = info,
            password = pin
        ))
        val result = TestSettings.testUserSubject.login.test(null, listOf(proof1))
        println(result)
        assert(result.session != null)
        val auth = TestSettings.testUserSubject.tokenToAuth(result.session!!, null)!!
        TestSettings.testUserSubject.self.implementation(AuthAndPathParts(auth, null, arrayOf()), Unit)
    }

    @Test
    fun testEmailOtpPasswordAll(): Unit = runBlocking {
        val info = TestSettings.proofEmail.start.test(null, TestSettings.testAdmin.await().email)
        val pinRegex = Regex("[A-Z][A-Z][A-Z][A-Z][A-Z][A-Z]")
        val pin = (TestSettings.email() as TestEmailClient).lastEmailSent?.also { println(it) }?.plainText?.let {
            pinRegex.find(it)?.value
        }!!
        val proof1 = TestSettings.proofEmail.prove.test(null, FinishProof(
            key = info,
            password = pin
        )
        )
        val result = TestSettings.testUserSubject.login.test(null, listOf(proof1))
        println(result)
        assert(result.session != null)
        val auth = TestSettings.testUserSubject.tokenToAuth(result.session!!, null)!!
        val self = TestSettings.testUserSubject.self.implementation(AuthAndPathParts(auth, null, arrayOf()), Unit)

        // Set up OTP
        TestSettings.proofOtp.establish.test(self, EstablishOtp("Test Label"))
        @Suppress("UNCHECKED_CAST") var secret = TestSettings.proofOtp.modelInfo.collection().findOne(
            condition { it.subjectType.eq(TestSettings.testUserSubject.handler.name) and it.subjectId.eq(self._id.toString()) }
        )!!
        assertNull(secret.lastUsedAt)
        run {
            // Can still log in with email pin only before confirmation
            assertNotNull(TestSettings.testUserSubject.login.test(null, listOf(proof1)).also { println(it) }.session)
        }
        TestSettings.proofOtp.confirm.test(self, secret.generator.generate())
        secret = TestSettings.proofOtp.modelInfo.collection().findOne(
            condition { it.subjectType.eq(TestSettings.testUserSubject.handler.name) and it.subjectId.eq(self._id.toString()) }
        )!!
        assertNotNull(secret.lastUsedAt)

        // Set up Password
        TestSettings.proofPassword.establish.test(self, EstablishPassword("test"))

        // Re-log in requires all
        val r1 = TestSettings.testUserSubject.login.test(null, listOf(proof1))
        assertNull(r1.session)
        assertTrue(r1.options.any { it.method == TestSettings.proofOtp.info })
        assertTrue(r1.options.any { it.method == TestSettings.proofPassword.info })
        val proof2 = TestSettings.proofOtp.prove.test(null, IdentificationAndPassword(
            type = TestSettings.subjectHandler.name,
            property = "${TestSettings.subjectHandler.name}/_id",
            value = r1.id.toString(),
            password = secret.generator.generate()
        ))
        assert(TestSettings.testUserSubject.proofHasher().verify(proof2))
        val proof3 = TestSettings.proofPassword.prove.test(null, IdentificationAndPassword(
            type = TestSettings.subjectHandler.name,
            property = "${TestSettings.subjectHandler.name}/_id",
            value = r1.id.toString(),
            password = "test"
        ))
        assert(TestSettings.testUserSubject.proofHasher().verify(proof3))
        val r2 = TestSettings.testUserSubject.login.test(null, listOf(proof1, proof2, proof3))
        assertNotNull(r2.session)

        // Set up Known Device
        val known = TestSettings.proofKnown.establish.test(self, Unit)

        // Can skip OTP using known device
        run {
            val r1 = TestSettings.testUserSubject.login.test(null, listOf(proof1))
            assertNull(r1.session)
            assertTrue(r1.options.any { it.method == TestSettings.proofOtp.info })
            assertTrue(r1.options.any { it.method == TestSettings.proofPassword.info })
            val proof2 = TestSettings.proofKnown.prove.test(null, known)
            assert(TestSettings.testUserSubject.proofHasher().verify(proof2))
            val proof3 = TestSettings.proofPassword.prove.test(
                null, IdentificationAndPassword(
                    type = TestSettings.subjectHandler.name,
                    property = "${TestSettings.subjectHandler.name}/_id",
                    value = r1.id.toString(),
                    password = "test"
                )
            )
            assert(TestSettings.testUserSubject.proofHasher().verify(proof3))
            val r2 = TestSettings.testUserSubject.login.test(null, listOf(proof1, proof2, proof3))
            assertNotNull(r2.session)
        }
    }

    @Test
    fun testEmailOtpPasswordAny(): Unit = runBlocking {
        val info = TestSettings.proofEmail.start.test(null, "notadmin@test.com")
        val pinRegex = Regex("[A-Z][A-Z][A-Z][A-Z][A-Z][A-Z]")
        val pin = (TestSettings.email() as TestEmailClient).lastEmailSent?.also { println(it) }?.plainText?.let {
            pinRegex.find(it)?.value
        }!!
        val proof1 = TestSettings.proofEmail.prove.test(null, FinishProof(
            key = info,
            password = pin
        ))
        val result = TestSettings.testUserSubject.login.test(null, listOf(proof1))
        println(result)
        assert(result.session != null)
        val auth = TestSettings.testUserSubject.tokenToAuth(result.session!!, null)!!
        val self = TestSettings.testUserSubject.self.implementation(AuthAndPathParts(auth, null, arrayOf()), Unit)

        // Set up OTP
        TestSettings.proofOtp.establish.test(self, EstablishOtp("Test Label"))
        @Suppress("UNCHECKED_CAST") var secret = TestSettings.proofOtp.modelInfo.collection().findOne(
            condition { it.subjectType.eq(TestSettings.testUserSubject.handler.name) and it.subjectId.eq(self._id.toString()) }
        )!!
        assertNull(secret.lastUsedAt)
        run {
            // Can still log in with email pin only before confirmation
            assertNotNull(TestSettings.testUserSubject.login.test(null, listOf(proof1)).session)
        }
        TestSettings.proofOtp.confirm.test(self, secret.generator.generate())
        secret = TestSettings.proofOtp.modelInfo.collection().findOne(
            condition { it.subjectType.eq(TestSettings.testUserSubject.handler.name) and it.subjectId.eq(self._id.toString()) }
        )!!
        assertNotNull(secret.lastUsedAt)

        // Set up Password
        TestSettings.proofPassword.establish.test(self, EstablishPassword("test"))

        // Set up phone
        TestSettings.userInfo.collection().updateOneById(self._id, modification { it.phoneNumber assign "8001002000" })

        // Can still log in with just an email
        run {
            val result = TestSettings.testUserSubject.login.test(null, listOf(proof1))
            println(result)
            assert(result.session != null)
        }

        // Can log in with phone
        run {
            val key = TestSettings.proofSms.start.test(null, "8001002000")
            val pin = pinRegex.find(TestSMSClient.lastMessageSent?.message ?: "")!!.value
            val proof = TestSettings.proofSms.prove.test(null, FinishProof(key, pin))
            val result = TestSettings.testUserSubject.login.test(null, listOf(proof))
            assert(result.session != null)
        }

        // Can log in with password alone
        run {
            val proof = TestSettings.proofPassword.prove.test(null, IdentificationAndPassword(TestSettings.subjectHandler.name, "email", self.email, "test"))
            val result = TestSettings.testUserSubject.login.test(null, listOf(proof))
            assert(result.session != null)
        }

        // Can log in with otp alone
        run {
            val proof = TestSettings.proofOtp.prove.test(null, IdentificationAndPassword(TestSettings.subjectHandler.name, "email", self.email, secret.generator.generate()))
            val result = TestSettings.testUserSubject.login.test(null, listOf(proof))
            assert(result.session != null)
        }

        // Can't log in with wrong phone
        run {
            val proofA = run {
                val key = TestSettings.proofSms.start.test(null, "8001002001")
                val pin = pinRegex.find(TestSMSClient.lastMessageSent?.message ?: "")!!.value
                TestSettings.proofSms.prove.test(null, FinishProof(key, pin))
            }
            val proofB = run {
                val key = TestSettings.proofEmail.start.test(null, "notadmin@test.com")
                val pin = (TestSettings.email() as TestEmailClient).lastEmailSent?.also { println(it) }?.plainText?.let {
                    pinRegex.find(it)?.value
                }!!
                TestSettings.proofEmail.prove.test(null, FinishProof(key, pin))
            }
            try {
                val result = TestSettings.testUserSubject.login.test(null, listOf(proofA, proofB))
                fail()
            } catch(e: Exception) {
                assertContains(e.message ?: "", "not related", ignoreCase = true)
            }
        }
    }

    @Test
    fun testPasswordSpam(): Unit = runBlocking {
        val info = TestSettings.proofEmail.start.test(null, "notadmin@test.com")
        val pinRegex = Regex("[A-Z][A-Z][A-Z][A-Z][A-Z][A-Z]")
        val pin = (TestSettings.email() as TestEmailClient).lastEmailSent?.also { println(it) }?.plainText?.let {
            pinRegex.find(it)?.value
        }!!
        val proof1 = TestSettings.proofEmail.prove.test(null, FinishProof(
            key = info,
            password = pin
        ))
        val result = TestSettings.testUserSubject.login.test(null, listOf(proof1))
        println(result)
        assert(result.session != null)
        val auth = TestSettings.testUserSubject.tokenToAuth(result.session!!, null)!!
        val self = TestSettings.testUserSubject.self.implementation(AuthAndPathParts(auth, null, arrayOf()), Unit)

        // Set up Password
        TestSettings.proofPassword.establish.test(self, EstablishPassword("test"))

        // Can log in with password alone
        var time: Instant = Clock.System.now()
        Clock.default = object: Clock {
            override fun now(): Instant = time
        }
        try {
            for(i in 0..10) {
                try {
                    TestSettings.proofPassword.prove.test(
                        null,
                        IdentificationAndPassword(TestSettings.subjectHandler.name, "email", self.email, "wrong")
                    )
                } catch(e: HttpStatusException) {
                    println(e.message)
                    if(e.message.contains("wait"))
                        break
                }
                time += 0.1.seconds
            }
            time += 5.minutes
            time += 5.seconds
            TestSettings.proofPassword.prove.test(
                null,
                IdentificationAndPassword(TestSettings.subjectHandler.name, "email", self.email, "test")
            )
        } finally {
            Clock.default = Clock.System
        }
    }

    @Test
    fun testFutureToken(): Unit = runBlocking {
        val future = TestSettings.testUserSubject.futureSessionToken(
            TestSettings.testUser.await()._id
        )
        TestSettings.testUserSubject.openSession.test(null, future)
    }

    @Test fun masquerade(): Unit = runBlocking {
        val (session, token) = TestSettings.testUserSubject.newSession(TestSettings.testAdmin.await()._id)
        HttpRequest(
            TestSettings.testUserSubject.self.route.endpoint,
            headers = HttpHeaders {
                set(HttpHeader.Authorization, "Bearer $token")
                set(HttpHeader.XMasquerade, "${TestSettings.subjectHandler.name}/${TestSettings.testUser.await()._id}")
            }
        ).authAny().let {
            if(it == null) fail()
            assertEquals(TestSettings.testUser.await()._id, it.rawId)
            assertEquals(session._id, it.sessionId)
        }
    }

    @Test fun testOauth(): Unit = runBlocking {
        val oauthClient = TestSettings.oauthClients.rest.insert.test(TestSettings.testAdmin.await(), OauthClient(
            _id = uuid().toString(),
            niceName = "Test",
            scopes = TestSettings.testUserSubject.self.authOptions.options.first()!!.scopes ?: setOf(),
            redirectUris = setOf("https://test.com")
        ))
        val oauthSecret = TestSettings.oauthClients.createSecret.test(TestSettings.testAdmin.await(), oauthClient._id, Unit)

        val code = TestSettings.testUserSubject.generateOauthCode.test(TestSettings.testUser.await(), OauthCodeRequest(
            response_type = "code",
            scope = oauthClient.scopes.joinToString(" "),
            redirect_uri = oauthClient.redirectUris.first(),
            client_id = oauthClient._id,
            access_type = OauthAccessType.offline,
        ))

        val tokenResponse = TestSettings.testUserSubject.token.test(null, OauthTokenRequest(
            code = code.code,
            grant_type = "authorization_code",
            client_id = oauthClient._id,
            client_secret = oauthSecret,
            redirect_uri = oauthClient.redirectUris.first()
        ))

        assertNotNull(TestSettings.testUserSubject.request(HttpRequest(
            TestSettings.testUserSubject.self.route.endpoint,
            headers = HttpHeaders { set(HttpHeader.Authorization, "Bearer ${tokenResponse.access_token}") }
        )))

        val self1 = TestSettings.testUserSubject.self(HttpRequest(
            TestSettings.testUserSubject.self.route.endpoint,
            headers = HttpHeaders { set(HttpHeader.Authorization, "Bearer ${tokenResponse.access_token}") }
        )).also { assertTrue(it.status.success) }
        val self2 = TestSettings.testUserSubject.self(HttpRequest(
            TestSettings.testUserSubject.self.route.endpoint,
            headers = HttpHeaders { set(HttpHeader.Authorization, "Bearer ${tokenResponse.refresh_token}") }
        )).also { assertTrue(it.status.success) }
        val ac2 = TestSettings.testUserSubject.token.test(null, OauthTokenRequest(
            refresh_token = tokenResponse.refresh_token!!,
            grant_type = "refresh_token",
            client_id = oauthClient._id,
            client_secret = oauthSecret,
        ))
        println(self1)
    }

}