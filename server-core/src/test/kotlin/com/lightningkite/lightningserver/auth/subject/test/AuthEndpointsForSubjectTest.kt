@file:UseContextualSerialization(UUID::class)

package com.lightningkite.lightningserver.auth.subject.test

import com.lightningkite.lightningdb.insertOne
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.auth.authAny
import com.lightningkite.lightningserver.auth.oauth.OauthAccessType
import com.lightningkite.lightningserver.auth.oauth.OauthClient
import com.lightningkite.lightningserver.auth.oauth.OauthCodeRequest
import com.lightningkite.lightningserver.auth.oauth.OauthTokenRequest
import com.lightningkite.lightningserver.auth.proof.*
import com.lightningkite.lightningserver.auth.subject.Session
import com.lightningkite.lightningserver.email.TestEmailClient
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.typed.AuthAndPathParts
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.uuid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.UseContextualSerialization
import org.junit.Test
import java.util.*
import kotlin.test.*

class AuthEndpointsForSubjectTest {

    @Test
    fun test(): Unit = runBlocking {
        val info = TestSettings.proofEmail.start.implementation(AuthAndPathParts(null, null, arrayOf()), "test@test.com")
        val pinRegex = Regex("[0-9][0-9][0-9][0-9][0-9][0-9]")
        val pin = (TestSettings.email() as TestEmailClient).lastEmailSent?.plainText?.let {
            pinRegex.find(it)?.value
        }!!
        val proof1 = TestSettings.proofEmail.prove.implementation(AuthAndPathParts(null, null, arrayOf()), ProofEvidence(
            value = info,
            secret = pin
        ))
        val result = TestSettings.testUserSubject.login.implementation(AuthAndPathParts(null, null, arrayOf()), listOf(proof1))
        println(result)
        assert(result.session != null)
    }

    @Ignore
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