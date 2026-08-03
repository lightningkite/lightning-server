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
import com.sun.net.httpserver.HttpServer
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.test.*
import kotlin.uuid.Uuid

/**
 * Exercises the OAuth token exchange itself: the outbound `client.post(tokenUrl)` call inside
 * [OauthProviderInfo.accessToken], driven through [OauthCallbackEndpoint.handle].
 *
 * [OauthCallbackSecurityTest] covers the CSRF `state` / PKCE bookkeeping (registration, single-use
 * consumption, forgery/replay rejection), but every one of its cases is rejected during state
 * validation, before `accessToken` ever runs. So the actual HTTP request sent to the token
 * endpoint - and in particular whether the `code_verifier` PKCE proves anything - was never
 * exercised, not even against a mock. This file closes that gap using a real loopback HTTP server
 * standing in for the provider, so the genuine `HttpClient` used in production round-trips over a
 * real socket exactly as it would against a live provider.
 *
 * NOT covered here: a real external OAuth server (e.g. Testcontainers + Keycloak) verifying our
 * request against actual RFC 7636/6749 server-side validation logic. That was investigated
 * separately and found infeasible in this sandbox/CI: no Docker available, and the macOS CI
 * runner doesn't support Testcontainers on Apple Silicon. A future PR adding a Linux/Docker-capable
 * CI job would need to add that end-to-end suite; tracked in plans/architecture-review-2026-07.md.
 */
class OauthTokenExchangeTest {

    /** Minimal loopback HTTP server standing in for the provider's token endpoint. */
    private class FakeTokenEndpoint(
        private val respond: (body: String) -> Pair<Int, String>,
    ) : AutoCloseable {
        data class Captured(val path: String, val contentType: String?, val body: String)

        val capturedRequests: MutableList<Captured> = mutableListOf()

        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                capturedRequests += Captured(
                    path = exchange.requestURI.path,
                    contentType = exchange.requestHeaders.getFirst("Content-Type"),
                    body = body,
                )
                val (status, responseBody) = respond(body)
                val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        val tokenUrl: String get() = "http://127.0.0.1:${server.address.port}/token"

        override fun close() {
            server.stop(0)
        }
    }

    /** Parses `application/x-www-form-urlencoded` body content into a lookup map for assertions. */
    private fun parseFormBody(body: String): Map<String, String> =
        body.split("&").filter { it.isNotEmpty() }.associate {
            val key = it.substringBefore('=')
            val value = it.substringAfter('=', "")
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }

    private fun testServer(tokenUrl: String) = object : ServerBuilder() {
        val cache = setting("cache", Cache.Settings("ram"))

        /** Captures the [OauthResponse] `onAccess` (and thus the completed login flow) actually saw. */
        var lastAccessResponse: OauthResponse? = null

        val provider = OauthProviderInfo(
            niceName = "TestProvider",
            loginUrl = "https://provider.example/authorize",
            tokenUrl = tokenUrl,
            scopeForProfile = "email",
            mode = OauthResponseMode.query,
            getProfile = { _, _ -> ExternalProfile(email = "user@example.com") },
        )
        val callback: OauthCallbackEndpoint<Uuid> = path.path("cb") include OauthCallbackEndpoint(
            path = path,
            stateSerializer = serializerOrContextual<Uuid>(),
            oauthProviderInfo = provider,
            credentials = Runtime.Constant(OauthProviderCredentials("test-client-id", "test-client-secret")),
            cache = cache,
            onAccess = { response, _ ->
                lastAccessResponse = response
                HttpResponse.plainText("welcome:${response.access_token}")
            },
        )
    }

    @Test
    fun `successful token exchange sends code, credentials, redirect uri and matching pkce verifier, and completes login`() =
        runBlocking {
            FakeTokenEndpoint({ _ ->
                200 to """{"access_token":"at-123","token_type":"Bearer","scope":"email"}"""
            }).use { fake ->
                val server = testServer(fake.tokenUrl)
                server.test({}) {
                    // Register a real state + PKCE pair the way OauthProofEndpoints does at flow-start.
                    val callerState = Uuid.random()
                    val loginUrl = server.callback.loginUrl(callerState)
                    val params = Url(loginUrl).parameters
                    val nonce = params["state"]!!
                    val challengeSent = params["code_challenge"]!!
                    val record = server.cache().get<OauthCallbackEndpoint.FlowRecord>(server.callback.flowKey(nonce))!!
                    val verifier = record.codeVerifier!!
                    assertEquals(
                        challengeSent,
                        pkceCodeChallengeS256(verifier),
                        "sanity check: the stored verifier must actually hash to the sent challenge",
                    )

                    val httpResponse = server.callback.handle(OauthCode(code = "auth-code-xyz", state = nonce))

                    assertEquals("welcome:at-123", httpResponse.body!!.text())
                    assertEquals("at-123", server.lastAccessResponse?.access_token, "onAccess must complete the login flow with the exchanged token")

                    assertEquals(1, fake.capturedRequests.size)
                    val request = fake.capturedRequests.single()
                    assertEquals("/token", request.path)
                    assertEquals(ContentType.Application.FormUrlEncoded.toString(), request.contentType)
                    val form = parseFormBody(request.body)
                    assertEquals("auth-code-xyz", form["code"])
                    assertEquals("test-client-id", form["client_id"])
                    assertEquals("test-client-secret", form["client_secret"])
                    assertEquals("authorization_code", form["grant_type"])
                    assertEquals(
                        verifier,
                        form["code_verifier"],
                        "token request must carry the code_verifier matching the code_challenge registered at flow-start - this is the PKCE guarantee",
                    )
                }
            }
        }

    @Test
    fun `provider token error is surfaced as a clean BadRequestException, not a raw crash`() = runBlocking {
        FakeTokenEndpoint({ _ ->
            400 to """{"error":"invalid_grant","error_description":"The authorization code is invalid or expired."}"""
        }).use { fake ->
            val server = testServer(fake.tokenUrl)
            server.test({}) {
                val loginUrl = server.callback.loginUrl(Uuid.random())
                val nonce = Url(loginUrl).parameters["state"]!!

                val error = assertFailsWith<BadRequestException>(
                    "a provider token-endpoint error must surface as a clean BadRequestException, not an unrelated internal exception",
                ) {
                    server.callback.handle(OauthCode(code = "auth-code-xyz", state = nonce))
                }
                assertTrue(
                    error.message.orEmpty().contains("invalid_grant"),
                    "error message should communicate the standard OAuth error code: ${error.message}",
                )
                // The provider's free-text error_description (and any other raw response body content)
                // must not be relayed verbatim - only the standardized `error` code is safe to surface.
                assertFalse(error.message.orEmpty().contains("error_description"))
                assertFalse(error.message.orEmpty().contains("invalid or expired"))
            }
        }
    }
}
