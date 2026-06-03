package com.lightningkite.lightningserver.sessions.openid

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.GrantedScope
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.isSuperUser
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.encryption.fastHash
import com.lightningkite.lightningserver.encryption.secureHash
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.runtime.test.TestRunner
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.sessions.SessionManager
import com.lightningkite.lightningserver.sessions.token.PrivateTinyTokenFormat
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.get
import com.lightningkite.services.database.insertOne
import com.lightningkite.services.database.table
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * End-to-end and adversarial tests for the OpenID Connect provider, driven against a real
 * [TestRunner] server with a RAM database and cache.
 */
class OpenIdProviderTest {

    @Serializable
    data class OidcUser(
        override val _id: Uuid = Uuid.random(),
        val email: String = "",
        val name: String = "",
    ) : HasId<Uuid> {
        companion object : PrincipalType<OidcUser, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<OidcUser> = serializer()
            val users = mutableMapOf<Uuid, OidcUser>()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): OidcUser = users[id] ?: OidcUser(id)
        }
    }

    class OidcSessions(database: Runtime<Database>) : SessionManager<OidcUser, Uuid>(
        principal = OidcUser,
        database = database,
        tokenFormat = Runtime { PrivateTinyTokenFormat() },
    ) {
        context(server: ServerRuntime)
        override suspend fun sessionExpiration(subject: OidcUser): Instant? = now() + 30.days

        context(server: ServerRuntime)
        override suspend fun sessionStaleAfter(subject: OidcUser): Duration? = null
    }

    private val testBasis = SecretBasis()

    /** Named server fixture so tests can share typed helpers. */
    inner class Fixture : ServerBuilder() {
        // Make the OauthClient admin endpoints (default IsSuperUser) reachable from tests with any
        // authenticated user, so createSecret can be exercised end-to-end.
        init { AuthRequirement.isSuperUser = AuthRequirement.Authenticated() }

        val database = setting("database", Database.Settings("ram"))
        val cache = setting("cache", Cache.Settings("ram"))
        val sessions = path.path("auth") include OidcSessions(database)
        val clients = path.path("oauth-clients") include OauthClientEndpoints(database)
        val openId = path.path("oauth") include OpenIdProviderEndpoints(
            sessions = sessions,
            database = database,
            cache = cache,
            // ES256 signing key derived deterministically from the secret basis (persistent by construction).
            signingKey = RuntimeDeferred.Cached { testBasis.oidcSigner() },
            getUserClaims = {
                IdTokenClaims(iss = "", sub = it._id.toString(), aud = "", exp = 0, iat = 0, email = it.email, name = it.name)
            },
            issuerUrl = Runtime { "https://issuer.example.com" },
            oauthBaseUrl = Runtime { "https://issuer.example.com/oauth" },
            authorizationUiUrl = Runtime { "https://app.example.com/authorize" },
        )
    }

    private fun freshUser(email: String = "alice@example.com", name: String = "Alice Example"): Uuid {
        OidcUser.users.clear()
        val id = Uuid.random()
        OidcUser.users[id] = OidcUser(id, email, name)
        return id
    }

    private fun pkcePair(): Pair<String, String> {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()))
        return verifier to challenge
    }

    private fun codeFrom(redirectUri: String): String = redirectUri.substringAfter("code=").substringBefore("&")

    companion object {
        const val CALLBACK = "https://app.example.com/callback"
    }

    private fun authReq(
        clientId: String,
        scope: String = "openid",
        challenge: String? = null,
        method: String? = "S256",
        redirect: String = CALLBACK,
        state: String? = null,
        nonce: String? = null,
        responseType: String = "code",
    ) = AuthorizationRequest(
        response_type = responseType,
        client_id = clientId,
        redirect_uri = redirect,
        scope = scope,
        state = state,
        nonce = nonce,
        code_challenge = challenge,
        code_challenge_method = challenge?.let { method },
    )

    // ----- typed helpers, available inside server.test { } -----------------------------------

    context(test: TestRunner<*>)
    private suspend fun Fixture.addClient(
        id: String,
        scopes: Set<String> = setOf(OpenIdScopes.OPENID),
        redirect: String = CALLBACK,
        trusted: Boolean = false,
        requirePkce: Boolean = false,
        secretHash: String? = null,
        postLogout: Set<String> = setOf(),
    ) {
        database().table<OauthClient>().insertOne(
            OauthClient(
                _id = id,
                niceName = id,
                scopes = scopes,
                redirectUris = setOf(redirect),
                postLogoutRedirectUris = postLogout,
                trusted = trusted,
                requirePkce = requirePkce,
                secrets = secretHash?.let { setOf(OauthClientSecret(createdAt = now(), masked = "x", secretHash = it)) } ?: setOf(),
            )
        )
    }

    context(test: TestRunner<*>)
    private fun Fixture.userAuth(id: Uuid): Authentication<OidcUser> =
        Authentication(principalType = OidcUser, id = id, sessionId = null, scopes = setOf(GrantedScope.root))

    /** Same authentication, widened to the admin endpoints' `HasId<*>` subject type. */
    context(test: TestRunner<*>)
    @Suppress("UNCHECKED_CAST")
    private fun Fixture.adminAuth(id: Uuid): Authentication<HasId<*>> = userAuth(id) as Authentication<HasId<*>>

    /** Runs prepare (+approve if consent is required) and returns the issued authorization code. */
    context(test: TestRunner<*>)
    private suspend fun Fixture.authorize(req: AuthorizationRequest, user: Uuid): String {
        val prepared = openId.authorizePrepare.test(userAuth(user), req)
        val redirect = prepared.redirectUri ?: openId.authorizeApprove.test(
            userAuth(user),
            AuthorizeApproveRequest(req, req.scope.split(' ').filter { it.isNotBlank() }.toSet()),
        ).redirectUri
        return codeFrom(redirect)
    }

    // ----- happy paths ------------------------------------------------------------------------

    @Test
    fun `full authorization code flow with consent, token, and userinfo`() = runBlocking {
        val userId = freshUser()
        val server = Fixture()
        server.test({}) {
            server.addClient(
                "demo-client",
                scopes = setOf(OpenIdScopes.OPENID, OpenIdScopes.PROFILE, OpenIdScopes.EMAIL, OpenIdScopes.OFFLINE_ACCESS),
            )
            val (verifier, challenge) = pkcePair()
            val request = authReq("demo-client", "openid profile email offline_access", challenge, state = "xyz", nonce = "n-0S6_WzA2Mj")

            // Prepare -> consent required (client is not trusted)
            val prepare = server.openId.authorizePrepare.test(server.userAuth(userId), request)
            assertNull(prepare.redirectUri)
            val consent = assertNotNull(prepare.consent)
            assertEquals("demo-client", consent.clientName)
            assertTrue(OpenIdScopes.EMAIL in consent.requestedScopes)
            assertTrue(consent.scopeDescriptions.containsKey(OpenIdScopes.EMAIL))

            // Approve -> code
            val approve = server.openId.authorizeApprove.test(
                server.userAuth(userId), AuthorizeApproveRequest(request, consent.requestedScopes),
            )
            assertTrue(approve.redirectUri.startsWith("$CALLBACK?"))
            assertTrue(approve.redirectUri.contains("state=xyz"))
            val code = codeFrom(approve.redirectUri)

            // Token exchange
            val tokens = server.openId.token.test(
                null,
                TokenRequest(GrantTypes.AUTHORIZATION_CODE, code = code, redirect_uri = CALLBACK, client_id = "demo-client", code_verifier = verifier),
            )
            assertTrue(tokens.access_token.isNotBlank())
            assertNotNull(tokens.refresh_token)
            val idToken = assertNotNull(tokens.id_token)

            val payload = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(idToken.split('.')[1]))) as JsonObject
            assertEquals("https://issuer.example.com", payload["iss"]!!.jsonPrimitive.content)
            assertEquals("demo-client", payload["aud"]!!.jsonPrimitive.content)
            assertEquals(userId.toString(), payload["sub"]!!.jsonPrimitive.content)
            assertEquals("n-0S6_WzA2Mj", payload["nonce"]!!.jsonPrimitive.content)

            // UserInfo, filtered by granted scopes
            val accessAuth = assertNotNull(server.sessions.tokenFormat().read(server.sessions.principal, tokens.access_token))
            val info = server.openId.userinfo.test(accessAuth, Unit)
            assertEquals(userId.toString(), info.sub)
            assertEquals("alice@example.com", info.email)
            assertEquals("Alice Example", info.name)
            assertNull(info.phone_number)
            assertNull(info.address)

            // Refresh grant
            val refreshed = server.openId.token.test(
                null, TokenRequest(GrantTypes.REFRESH_TOKEN, refresh_token = tokens.refresh_token, client_id = "demo-client"),
            )
            assertTrue(refreshed.access_token.isNotBlank())
            assertNotNull(server.sessions.tokenFormat().read(server.sessions.principal, refreshed.access_token))
        }
    }

    @Test
    fun `trusted client skips consent and discovery and jwks are published`() = runBlocking {
        val userId = freshUser("bob@example.com", "Bob Example")
        val server = Fixture()
        server.test({}) {
            server.addClient("trusted-client", scopes = setOf(OpenIdScopes.OPENID, OpenIdScopes.PROFILE), trusted = true)
            val (_, challenge) = pkcePair()
            val prepare = server.openId.authorizePrepare.test(server.userAuth(userId), authReq("trusted-client", "openid profile", challenge))
            assertNull(prepare.consent, "Trusted client should skip consent")
            assertTrue(codeFrom(assertNotNull(prepare.redirectUri)).isNotBlank())

            val metadata = server.openId.discovery.test(null, Unit)
            assertEquals("https://issuer.example.com", metadata.issuer)
            assertEquals("https://app.example.com/authorize", metadata.authorization_endpoint)
            assertEquals("https://issuer.example.com/oauth/token", metadata.token_endpoint)
            assertTrue("ES256" in metadata.id_token_signing_alg_values_supported)

            val jwks = server.openId.jwks.test(null, Unit)
            val key = jwks.keys.single()
            assertEquals("EC", key.kty)
            assertEquals("P-256", key.crv)
            assertEquals("default", key.kid)
            assertNotNull(key.x); assertNotNull(key.y)
        }
    }

    @Test
    fun `confidential client authenticates with secret and offline_access is required for refresh tokens`() = runBlocking {
        val userId = freshUser()
        val server = Fixture()
        server.test({}) {
            val secret = "s3cret-value"
            // Confidential client (has a secret) so no PKCE required; only "openid" allowed (no offline_access).
            server.addClient("conf", scopes = setOf(OpenIdScopes.OPENID), trusted = true, secretHash = secret.secureHash())

            val code = server.authorize(authReq("conf", "openid"), userId)

            // Wrong secret -> invalid_client
            val wrong = assertFailsWith<BadRequestException> {
                server.openId.token.test(null, TokenRequest(GrantTypes.AUTHORIZATION_CODE, code = server.authorize(authReq("conf"), userId), redirect_uri = CALLBACK, client_id = "conf", client_secret = "nope"))
            }
            assertEquals(OAuth2ErrorCodes.INVALID_CLIENT, wrong.detail)

            // Missing secret -> invalid_client
            assertFailsWith<BadRequestException> {
                server.openId.token.test(null, TokenRequest(GrantTypes.AUTHORIZATION_CODE, code = server.authorize(authReq("conf"), userId), redirect_uri = CALLBACK, client_id = "conf"))
            }.let { assertEquals(OAuth2ErrorCodes.INVALID_CLIENT, it.detail) }

            // Correct secret -> tokens, but no refresh token (offline_access not granted)
            val tokens = server.openId.token.test(
                null, TokenRequest(GrantTypes.AUTHORIZATION_CODE, code = code, redirect_uri = CALLBACK, client_id = "conf", client_secret = secret),
            )
            assertTrue(tokens.access_token.isNotBlank())
            assertNull(tokens.refresh_token, "No refresh token without offline_access")
        }
    }

    // ----- authorization validation -----------------------------------------------------------

    @Test
    fun `authorization rejects bad requests`() = runBlocking {
        val userId = freshUser()
        val server = Fixture()
        server.test({}) {
            val (_, challenge) = pkcePair()
            val u = server.userAuth(userId)
            server.addClient("c", scopes = setOf(OpenIdScopes.OPENID, OpenIdScopes.PROFILE), trusted = true)
            server.addClient("http-client", scopes = setOf(OpenIdScopes.OPENID), redirect = "http://evil.example.com/cb", trusted = true)
            server.addClient("public", scopes = setOf(OpenIdScopes.OPENID), trusted = true)

            // Unknown client
            assertEquals(OAuth2ErrorCodes.INVALID_CLIENT, assertFailsWith<BadRequestException> {
                server.openId.authorizePrepare.test(u, authReq("nope", challenge = challenge))
            }.detail)
            // Unregistered redirect uri
            assertEquals(OAuth2ErrorCodes.INVALID_REQUEST, assertFailsWith<BadRequestException> {
                server.openId.authorizePrepare.test(u, authReq("c", challenge = challenge, redirect = "https://app.example.com/elsewhere"))
            }.detail)
            // Insecure (non-localhost http) redirect uri (registered, but not https)
            assertEquals(OAuth2ErrorCodes.INVALID_REQUEST, assertFailsWith<BadRequestException> {
                server.openId.authorizePrepare.test(u, authReq("http-client", challenge = challenge, redirect = "http://evil.example.com/cb"))
            }.detail)
            // Missing openid scope
            assertEquals(OAuth2ErrorCodes.INVALID_SCOPE, assertFailsWith<BadRequestException> {
                server.openId.authorizePrepare.test(u, authReq("c", scope = "profile", challenge = challenge))
            }.detail)
            // Scope not allowed for client
            assertEquals(OAuth2ErrorCodes.INVALID_SCOPE, assertFailsWith<BadRequestException> {
                server.openId.authorizePrepare.test(u, authReq("c", scope = "openid email", challenge = challenge))
            }.detail)
            // Unsupported response type
            assertEquals(OAuth2ErrorCodes.UNSUPPORTED_RESPONSE_TYPE, assertFailsWith<BadRequestException> {
                server.openId.authorizePrepare.test(u, authReq("c", challenge = challenge, responseType = "token"))
            }.detail)
            // Public client without PKCE (challenge omitted)
            assertEquals(OAuth2ErrorCodes.INVALID_REQUEST, assertFailsWith<BadRequestException> {
                server.openId.authorizePrepare.test(u, authReq("public"))
            }.detail)
            // Plain PKCE method rejected
            assertEquals(OAuth2ErrorCodes.INVALID_REQUEST, assertFailsWith<BadRequestException> {
                server.openId.authorizePrepare.test(u, authReq("c", challenge = challenge, method = "plain"))
            }.detail)
        }
    }

    @Test
    fun `requirePkce confidential client must use PKCE`() = runBlocking {
        val userId = freshUser()
        val server = Fixture()
        server.test({}) {
            server.addClient("strict", scopes = setOf(OpenIdScopes.OPENID), trusted = true, requirePkce = true, secretHash = "x".secureHash())
            val e = assertFailsWith<BadRequestException> {
                server.openId.authorizePrepare.test(server.userAuth(userId), authReq("strict")) // no challenge
            }
            assertEquals(OAuth2ErrorCodes.INVALID_REQUEST, e.detail)
        }
    }

    @Test
    fun `approve enforces openid and a subset of requested scopes`() = runBlocking {
        val userId = freshUser()
        val server = Fixture()
        server.test({}) {
            server.addClient("c", scopes = setOf(OpenIdScopes.OPENID, OpenIdScopes.PROFILE, OpenIdScopes.EMAIL))
            val (_, challenge) = pkcePair()
            val req = authReq("c", "openid profile", challenge)

            // Granting a scope that was not requested
            assertFailsWith<BadRequestException> {
                server.openId.authorizeApprove.test(server.userAuth(userId), AuthorizeApproveRequest(req, setOf("openid", "email")))
            }.let { assertEquals(OAuth2ErrorCodes.INVALID_SCOPE, it.detail) }

            // Granting without openid
            assertFailsWith<BadRequestException> {
                server.openId.authorizeApprove.test(server.userAuth(userId), AuthorizeApproveRequest(req, setOf("profile")))
            }.let { assertEquals(OAuth2ErrorCodes.INVALID_SCOPE, it.detail) }
        }
    }

    @Test
    fun `consent is remembered for subsequent authorizations`() = runBlocking {
        val userId = freshUser()
        val server = Fixture()
        server.test({}) {
            server.addClient("c", scopes = setOf(OpenIdScopes.OPENID, OpenIdScopes.PROFILE))
            val (_, challenge) = pkcePair()
            val req = authReq("c", "openid profile", challenge)

            // First time: consent required, then approved
            assertNotNull(server.openId.authorizePrepare.test(server.userAuth(userId), req).consent)
            server.openId.authorizeApprove.test(server.userAuth(userId), AuthorizeApproveRequest(req, setOf("openid", "profile")))

            // Second time: no consent, immediate code
            val again = server.openId.authorizePrepare.test(server.userAuth(userId), req)
            assertNull(again.consent, "Consent should be remembered")
            assertNotNull(again.redirectUri)
        }
    }

    // ----- token endpoint validation ----------------------------------------------------------

    @Test
    fun `token endpoint enforces code single-use, PKCE, redirect, and grant type`() = runBlocking {
        val userId = freshUser()
        val server = Fixture()
        server.test({}) {
            server.addClient("c", scopes = setOf(OpenIdScopes.OPENID), trusted = true)
            val (verifier, challenge) = pkcePair()

            // Single-use: first exchange succeeds, second with same code fails
            val code = server.authorize(authReq("c", challenge = challenge), userId)
            server.openId.token.test(null, TokenRequest(GrantTypes.AUTHORIZATION_CODE, code = code, redirect_uri = CALLBACK, client_id = "c", code_verifier = verifier))
            assertFailsWith<BadRequestException> {
                server.openId.token.test(null, TokenRequest(GrantTypes.AUTHORIZATION_CODE, code = code, redirect_uri = CALLBACK, client_id = "c", code_verifier = verifier))
            }.let { assertEquals(OAuth2ErrorCodes.INVALID_GRANT, it.detail) }

            // Wrong PKCE verifier
            val code2 = server.authorize(authReq("c", challenge = challenge), userId)
            assertFailsWith<BadRequestException> {
                server.openId.token.test(null, TokenRequest(GrantTypes.AUTHORIZATION_CODE, code = code2, redirect_uri = CALLBACK, client_id = "c", code_verifier = "wrong-verifier"))
            }.let { assertEquals(OAuth2ErrorCodes.INVALID_GRANT, it.detail) }

            // Redirect URI mismatch
            val code3 = server.authorize(authReq("c", challenge = challenge), userId)
            assertFailsWith<BadRequestException> {
                server.openId.token.test(null, TokenRequest(GrantTypes.AUTHORIZATION_CODE, code = code3, redirect_uri = "https://app.example.com/other", client_id = "c", code_verifier = verifier))
            }.let { assertEquals(OAuth2ErrorCodes.INVALID_GRANT, it.detail) }

            // Unsupported grant type
            assertFailsWith<BadRequestException> {
                server.openId.token.test(null, TokenRequest("password", client_id = "c"))
            }.let { assertEquals(OAuth2ErrorCodes.UNSUPPORTED_GRANT_TYPE, it.detail) }
        }
    }

    @Test
    fun `refresh grant rejects a token issued to a different client`() = runBlocking {
        val userId = freshUser()
        val server = Fixture()
        server.test({}) {
            server.addClient("a", scopes = setOf(OpenIdScopes.OPENID, OpenIdScopes.OFFLINE_ACCESS), trusted = true)
            server.addClient("b", scopes = setOf(OpenIdScopes.OPENID, OpenIdScopes.OFFLINE_ACCESS), trusted = true)
            val (verifier, challenge) = pkcePair()
            val code = server.authorize(authReq("a", "openid offline_access", challenge), userId)
            val tokens = server.openId.token.test(null, TokenRequest(GrantTypes.AUTHORIZATION_CODE, code = code, redirect_uri = CALLBACK, client_id = "a", code_verifier = verifier))

            assertFailsWith<BadRequestException> {
                server.openId.token.test(null, TokenRequest(GrantTypes.REFRESH_TOKEN, refresh_token = tokens.refresh_token, client_id = "b"))
            }.let { assertEquals(OAuth2ErrorCodes.INVALID_CLIENT, it.detail) }
        }
    }

    // ----- end session, introspection, revocation ---------------------------------------------

    @Test
    fun `end session terminates the access token's session and validates the redirect`() = runBlocking {
        val userId = freshUser("carol@example.com", "Carol Example")
        val server = Fixture()
        server.test({}) {
            server.addClient("logout-client", scopes = setOf(OpenIdScopes.OPENID), trusted = true, postLogout = setOf("https://app.example.com/loggedout"))
            val (verifier, challenge) = pkcePair()
            val code = server.authorize(authReq("logout-client", challenge = challenge), userId)
            val tokens = server.openId.token.test(null, TokenRequest(GrantTypes.AUTHORIZATION_CODE, code = code, redirect_uri = CALLBACK, client_id = "logout-client", code_verifier = verifier))
            val accessAuth = assertNotNull(server.sessions.tokenFormat().read(server.sessions.principal, tokens.access_token))
            val sessionId = Uuid.parse(accessAuth.sessionId!!)

            // Unregistered post-logout uri is rejected
            assertFailsWith<BadRequestException> {
                server.openId.endSession.test(accessAuth, EndSessionRequest(post_logout_redirect_uri = "https://evil.example.com"))
            }.let { assertEquals(OAuth2ErrorCodes.INVALID_REQUEST, it.detail) }

            // Registered uri works and echoes state
            val result = server.openId.endSession.test(accessAuth, EndSessionRequest(post_logout_redirect_uri = "https://app.example.com/loggedout", state = "s1"))
            assertEquals("https://app.example.com/loggedout?state=s1", result.redirectUri)
            assertNotNull(assertNotNull(server.sessions.sessionInfo.table().get(sessionId)).terminated)
        }
    }

    @Test
    fun `introspect and revoke operate on the backing session`() = runBlocking {
        val userId = freshUser("dave@example.com", "Dave Example")
        val server = Fixture()
        server.test({}) {
            server.addClient("rp", scopes = setOf(OpenIdScopes.OPENID, OpenIdScopes.OFFLINE_ACCESS), trusted = true)
            server.addClient("other", scopes = setOf(OpenIdScopes.OPENID), trusted = true)
            val (verifier, challenge) = pkcePair()
            val code = server.authorize(authReq("rp", "openid offline_access", challenge), userId)
            val tokens = server.openId.token.test(null, TokenRequest(GrantTypes.AUTHORIZATION_CODE, code = code, redirect_uri = CALLBACK, client_id = "rp", code_verifier = verifier))
            val refresh = assertNotNull(tokens.refresh_token)

            // Active token introspects with metadata
            val active = server.openId.introspect.test(null, IntrospectionRequest(token = tokens.access_token, client_id = "rp"))
            assertTrue(active.active)
            assertEquals("rp", active.client_id)
            assertEquals(userId.toString(), active.sub)
            assertTrue(active.scope!!.contains(OpenIdScopes.OPENID))

            // Unrecognized token, and a token belonging to another client, are inactive
            assertFalse(server.openId.introspect.test(null, IntrospectionRequest(token = "not-a-real-token", client_id = "rp")).active)
            assertFalse(server.openId.introspect.test(null, IntrospectionRequest(token = tokens.access_token, client_id = "other")).active)

            // Revoking the refresh token terminates the session; the access token then introspects inactive
            server.openId.revoke.test(null, RevocationRequest(token = refresh, client_id = "rp"))
            assertFalse(server.openId.introspect.test(null, IntrospectionRequest(token = tokens.access_token, client_id = "rp")).active)
            // And the refresh grant no longer works
            assertFailsWith<BadRequestException> {
                server.openId.token.test(null, TokenRequest(GrantTypes.REFRESH_TOKEN, refresh_token = refresh, client_id = "rp"))
            }.let { assertEquals(OAuth2ErrorCodes.INVALID_GRANT, it.detail) }
        }
    }

    // ----- client secret generation & rate limiting -------------------------------------------

    @Test
    fun `created client secret is a fast hash and authenticates at the token endpoint`() = runBlocking {
        val userId = freshUser()
        val server = Fixture()
        server.test({}) {
            server.addClient("rotating", scopes = setOf(OpenIdScopes.OPENID), trusted = true)

            // Mint a secret via the admin endpoint (CSPRNG-generated, returned once in plaintext).
            val secret = server.clients.createSecret.test("rotating", server.adminAuth(userId), Unit)
            assertTrue(secret.isNotBlank())

            // Stored as a salted SHA-256 (fast) hash — not PBKDF2 (slow, DoS-prone), not plaintext.
            val stored = assertNotNull(server.database().table<OauthClient>().get("rotating"))
            val storedSecret = stored.secrets.single()
            assertTrue(
                storedSecret.secretHash.startsWith("SHA256."),
                "high-entropy client secret should use fastHash, was '${storedSecret.secretHash.substringBefore('.')}'",
            )
            assertNotEquals(secret, storedSecret.secretHash, "secret must be stored hashed, never in plaintext")

            // masked is a last-4 recognition hint derived from the plaintext (not a constant, not the whole secret).
            assertEquals("…" + secret.takeLast(4), storedSecret.masked)
            assertFalse(secret in storedSecret.masked, "masked must not contain the full secret")

            // The plaintext authenticates a confidential token exchange...
            val code = server.authorize(authReq("rotating", "openid"), userId)
            val tokens = server.openId.token.test(
                null,
                TokenRequest(GrantTypes.AUTHORIZATION_CODE, code = code, redirect_uri = CALLBACK, client_id = "rotating", client_secret = secret),
            )
            assertTrue(tokens.access_token.isNotBlank())

            // ...and a wrong secret is rejected.
            val code2 = server.authorize(authReq("rotating", "openid"), userId)
            assertFailsWith<BadRequestException> {
                server.openId.token.test(null, TokenRequest(GrantTypes.AUTHORIZATION_CODE, code = code2, redirect_uri = CALLBACK, client_id = "rotating", client_secret = "wrong-secret"))
            }.let { assertEquals(OAuth2ErrorCodes.INVALID_CLIENT, it.detail) }
        }
    }

    @Test
    fun `token endpoint rate-limits repeated failed attempts`() = runBlocking {
        freshUser()
        val server = Fixture()
        server.test({}) {
            server.addClient("rl", scopes = setOf(OpenIdScopes.OPENID), trusted = true, secretHash = "correct".fastHash())

            // 10 failures are allowed (each an invalid_grant for the bogus code)...
            repeat(10) {
                assertFailsWith<BadRequestException> {
                    server.openId.token.test(null, TokenRequest(GrantTypes.AUTHORIZATION_CODE, code = "bogus", client_id = "rl", client_secret = "correct"))
                }
            }
            // ...the 11th is throttled rather than processed.
            val blocked = assertFailsWith<BadRequestException> {
                server.openId.token.test(null, TokenRequest(GrantTypes.AUTHORIZATION_CODE, code = "bogus", client_id = "rl", client_secret = "correct"))
            }
            assertTrue(
                blocked.message?.contains("Too many attempts") == true,
                "expected a rate-limit message, got: ${blocked.message}",
            )
        }
    }

    @Test
    fun `deterministic signing key is stable across derivations`() = runBlocking {
        // The same secret basis must yield the same JWKS (persistent-by-construction key).
        val server1 = Fixture()
        val server2 = Fixture()
        var jwks1 = ""
        server1.test({}) { jwks1 = server1.openId.jwks.test(null, Unit).keys.single().let { "${it.x}:${it.y}" } }
        var jwks2 = ""
        server2.test({}) { jwks2 = server2.openId.jwks.test(null, Unit).keys.single().let { "${it.x}:${it.y}" } }
        assertEquals(jwks1, jwks2, "Same secret basis should derive the same signing key")
    }
}
