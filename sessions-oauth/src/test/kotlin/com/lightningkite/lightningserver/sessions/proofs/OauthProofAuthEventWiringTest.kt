package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.AuthEventReporter
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.sessions.proofs.oauth.*
import com.lightningkite.services.cache.Cache
import com.sun.net.httpserver.HttpServer
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

/**
 * The OAuth callback's auth-event wiring, driven through a completed login flow.
 *
 * OAuth is an *acceptance*: a trusted third party checked a credential and the callback mints a proof
 * on the strength of it. That makes it the same kind of event as a password being accepted, and it
 * was recorded nowhere — the callback is a redirect target, not an authenticated endpoint, so no
 * other audit layer sees it either.
 *
 * A capturing reporter rather than the real audit log, because the point being proved here is that a
 * real endpoint reaches the seam; `AuthEventLogTest` covers what the writer then does with it, and
 * pulling `audit` into this module's test classpath to re-prove that would buy nothing.
 */
class OauthProofAuthEventWiringTest {

    private class CapturingReporter : AuthEventReporter {
        data class Event(val type: String, val principal: String?, val method: String?, val detail: String?)

        val events: MutableList<Event> = mutableListOf()

        context(runtime: ServerRuntime)
        override suspend fun report(
            type: String,
            principal: String?,
            actor: String?,
            sessionId: String?,
            sourceIp: String?,
            userAgent: String?,
            detail: String?,
            method: String?,
            methodProperty: String?,
        ) {
            events += Event(type, principal, method, detail)
        }
    }

    /** Minimal loopback HTTP server standing in for the provider's token endpoint. */
    private class FakeTokenEndpoint : AutoCloseable {
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val bytes = """{"access_token":"at-123","token_type":"Bearer","scope":"email"}"""
                    .toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        val tokenUrl: String get() = "http://127.0.0.1:${server.address.port}/token"

        override fun close() {
            server.stop(0)
        }
    }

    private fun testServer(tokenUrl: String, profileEmail: String?) = object : ServerBuilder() {
        val cache = setting("cache", Cache.Settings("ram"))
        val reporter = install(CapturingReporter())

        val provider = OauthProviderInfo(
            niceName = "TestProvider",
            loginUrl = "https://provider.example/authorize",
            tokenUrl = tokenUrl,
            scopeForProfile = "email",
            mode = OauthResponseMode.query,
            getProfile = { _, _ -> ExternalProfile(email = profileEmail) },
        )

        val oauth = path.path("oauth") include OauthProofEndpoints(
            provider = provider,
            cache = cache,
            credentials = Runtime.Constant(OauthProviderCredentials("client-id", "client-secret")),
            continueUiAuthUrl = { "https://app.example/continue" },
        )
    }

    /** Registers a real state + PKCE pair the way a login start does, and returns the callback nonce. */
    context(server: ServerRuntime)
    private suspend fun nonceFor(callback: OauthCallbackEndpoint<Uuid>): String =
        Url(callback.loginUrl(Uuid.random())).parameters["state"]!!

    @Test
    fun `a completed oauth login is recorded as an accepted proof naming the identity`() = runBlocking {
        FakeTokenEndpoint().use { fake ->
            val server = testServer(fake.tokenUrl, profileEmail = "user@example.com")
            server.test({}) {
                server.oauth.callback.handle(OauthCode(code = "auth-code-xyz", state = nonceFor(server.oauth.callback)))

                val event = server.reporter.events.single()
                assertEquals("ProofAccepted", event.type)
                assertEquals("user@example.com", event.principal)
                assertEquals("testprovider", event.method)
            }
        }
    }

    /**
     * The provider authenticated somebody and returned nothing this server can identify them by. Not
     * a subject that failed to resolve — there was never anything to resolve — so the row says the
     * attempt could not be read as identifying anyone, and names no principal.
     */
    @Test
    fun `an oauth profile with no identifying property is recorded as a rejection`() = runBlocking {
        FakeTokenEndpoint().use { fake ->
            val server = testServer(fake.tokenUrl, profileEmail = null)
            server.test({}) {
                assertFailsWith<BadRequestException> {
                    server.oauth.callback.handle(
                        OauthCode(code = "auth-code-xyz", state = nonceFor(server.oauth.callback))
                    )
                }

                val event = server.reporter.events.single()
                assertEquals("ProofRejected", event.type)
                assertEquals("MalformedRequest", event.detail)
                assertEquals("testprovider", event.method)
                assertEquals(null, event.principal)
            }
        }
    }
}
