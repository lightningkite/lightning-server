package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.sessions.proofs.extensions.makeProof
import com.lightningkite.lightningserver.sessions.proofs.oauth.*
import com.lightningkite.services.cache.Cache
import com.sun.net.httpserver.HttpServer
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class OauthProofEndpointsTest {

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

    private fun testServer(
        tokenUrl: String,
        property: String,
        makeProof: suspend context(ServerRuntime, ProofMethod) (ExternalProfile) -> Proof,
    ) = object : ServerBuilder() {
        val cache = setting("cache", Cache.Settings("ram"))
        var issued: Proof? = null

        val provider = OauthProviderInfo(
            niceName = "TestProvider",
            loginUrl = "https://provider.example/authorize",
            tokenUrl = tokenUrl,
            scopeForProfile = "profile",
            mode = OauthResponseMode.query,
            getProfile = { _, _ -> ExternalProfile(username = "octocat") },
        )

        val oauth = path.path("oauth") include OauthProofEndpoints(
            provider = provider,
            cache = cache,
            credentials = Runtime.Constant(OauthProviderCredentials("client-id", "client-secret")),
            property = property,
            makeProof = makeProof,
            continueUiAuthUrl = { issued = it; "https://app.example/continue" },
        )
    }

    context(server: ServerRuntime)
    private suspend fun nonceFor(callback: OauthCallbackEndpoint<Uuid>): String =
        Url(callback.loginUrl(Uuid.random())).parameters["state"]!!

    @Test
    fun `a proof of a property other than email is accepted at login`() = runBlocking {
        FakeTokenEndpoint().use { fake ->
            val server = testServer(fake.tokenUrl, property = "username") { profile ->
                val username = profile.username ?: throw BadRequestException("No username.")
                contextOf<ProofMethod>().proofSigner.await().makeProof(property = "username", value = username)
            }
            server.test({}) {
                execute { server.oauth.callback.handle(OauthCode(code = "c", state = nonceFor(server.oauth.callback))) }
                val proof = server.issued!!
                assertEquals("username", proof.property)
                assertTrue(execute { server.oauth.isValid(proof) })
            }
        }
    }

    @Test
    fun `a makeProof that proves an undeclared property fails at the callback`() = runBlocking {
        FakeTokenEndpoint().use { fake ->
            val server = testServer(fake.tokenUrl, property = "email") { profile ->
                contextOf<ProofMethod>().proofSigner.await().makeProof(property = "username", value = profile.username!!)
            }
            server.test({}) {
                assertFailsWith<IllegalStateException> {
                    execute { server.oauth.callback.handle(OauthCode(code = "c", state = nonceFor(server.oauth.callback))) }
                }
            }
        }
    }

    @Test
    fun `apple credentials can be turned into oauth credentials inside a Runtime lambda`() = runBlocking {
        val key = CryptographyProvider.Default.get(ECDSA).keyPairGenerator(EC.Curve.P256).generateKeyBlocking()
        val apple = OauthProviderCredentialsApple(
            serviceId = "com.example.service",
            teamId = "TEAM123456",
            keyId = "KEY1234567",
            keyString = key.privateKey.encodeToByteArrayBlocking(EC.PrivateKey.Format.PEM).decodeToString(),
        )
        val credentials: Runtime<OauthProviderCredentials> = Runtime { apple.toOauthProviderCredentials() }
        val server = object : ServerBuilder() {}
        server.test({}) {
            val resolved = execute { credentials() }
            assertEquals("com.example.service", resolved.id)
            assertEquals(3, resolved.secret.split('.').size)
        }
    }
}
