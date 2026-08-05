package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.serializerOrContextual
import com.lightningkite.lightningserver.sessions.proofs.oauth.*
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.get
import com.lightningkite.services.cache.getAndRemove
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*
import kotlin.uuid.Uuid

/**
 * Security tests for the OAuth callback: CSRF `state` validation/consumption and PKCE (RFC 7636).
 *
 * These exercise the parts of the flow that do not require a live provider. The token exchange and
 * profile fetch (outgoing HTTP) are never reached: every negative case is rejected during state
 * validation, which happens before any network call.
 */
class OauthCallbackSecurityTest {

    private fun testServer(supportsPkce: Boolean = true) = object : ServerBuilder() {
        val cache = setting("cache", Cache.Settings("ram"))
        val provider = OauthProviderInfo(
            niceName = "TestProvider",
            loginUrl = "https://provider.example/authorize",
            tokenUrl = "https://provider.example/token",
            scopeForProfile = "email",
            mode = OauthResponseMode.query,
            supportsPkce = supportsPkce,
            getProfile = { _, _ -> ExternalProfile(email = "user@example.com") },
        )
        val callback: OauthCallbackEndpoint<Uuid> = path.path("cb") include OauthCallbackEndpoint(
            stateSerializer = serializerOrContextual<Uuid>(),
            oauthProviderInfo = provider,
            credentials = Runtime.Constant(OauthProviderCredentials("client-id", "client-secret")),
            cache = cache,
            onAccess = { _, _ -> HttpResponse.plainText("ok") },
        )
    }

    @Test
    fun `login url carries pkce challenge and stored verifier hashes to it`() = runBlocking {
        val server = testServer()
        server.test({}) {
            val state = Uuid.random()
            val url = server.callback.loginUrl(state)
            val params = Url(url).parameters

            assertEquals("S256", params["code_challenge_method"])
            val challenge = params["code_challenge"]
            assertNotNull(challenge, "authorization URL must include a code_challenge")

            val nonce = params["state"]!!
            val record = server.cache().get<OauthCallbackEndpoint.FlowRecord>(server.callback.flowKey(nonce))
            assertNotNull(record, "loginUrl must persist the flow keyed by the state nonce")
            val verifier = assertNotNull(record.codeVerifier)
            assertEquals(challenge, pkceCodeChallengeS256(verifier), "stored verifier must hash to the challenge")
        }
    }

    @Test
    fun `pkce can be disabled per provider`() = runBlocking {
        val server = testServer(supportsPkce = false)
        server.test({}) {
            val url = server.callback.loginUrl(Uuid.random())
            val params = Url(url).parameters
            assertNull(params["code_challenge"])
            assertNull(params["code_challenge_method"])
            val record = server.cache().get<OauthCallbackEndpoint.FlowRecord>(server.callback.flowKey(params["state"]!!))
            assertNull(assertNotNull(record).codeVerifier)
        }
    }

    @Test
    fun `callback with unknown state is rejected`() = runBlocking {
        val server = testServer()
        server.test({}) {
            assertFailsWith<BadRequestException> {
                server.callback.handle(OauthCode(code = "any", state = "never-issued"))
            }
        }
    }

    @Test
    fun `callback with missing state is rejected`() = runBlocking {
        val server = testServer()
        server.test({}) {
            assertFailsWith<BadRequestException> {
                server.callback.handle(OauthCode(code = "any", state = null))
            }
        }
    }

    @Test
    fun `state is single-use`() = runBlocking {
        val server = testServer()
        server.test({}) {
            val url = server.callback.loginUrl(Uuid.random())
            val nonce = Url(url).parameters["state"]!!
            // Simulate the first callback consuming the flow (getAndRemove is the single-use mechanism).
            assertNotNull(server.cache().getAndRemove<OauthCallbackEndpoint.FlowRecord>(server.callback.flowKey(nonce)))
            // A second callback with the same state must be rejected before any token exchange.
            assertFailsWith<BadRequestException> {
                server.callback.handle(OauthCode(code = "any", state = nonce))
            }
        }
    }

    @Test
    fun `pkce challenge matches rfc 7636 test vector`() {
        // RFC 7636 Appendix B worked example.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            pkceCodeChallengeS256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `generated verifier meets rfc 7636 length and charset`() {
        repeat(50) {
            val v = generatePkceCodeVerifier()
            assertTrue(v.length in 43..128, "verifier length ${v.length} out of range")
            assertTrue(v.all { it.isLetterOrDigit() || it in "-._~" }, "verifier has non-unreserved chars: $v")
        }
    }
}
