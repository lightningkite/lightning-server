@file:OptIn(com.lightningkite.lightningserver.ExperimentalOauthProviderApi::class)

package com.lightningkite.lightningserver.sessions.openid

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.UnauthorizedException
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.encryption.fastHash
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.runtime.Execution
import com.lightningkite.lightningserver.runtime.engine
import com.lightningkite.lightningserver.runtime.handleRoot
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.runtime.test.TestRunner
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.sessions.SessionManager
import com.lightningkite.lightningserver.sessions.proofs.oauth.OauthClient
import com.lightningkite.lightningserver.sessions.proofs.oauth.OauthClientEndpoints
import com.lightningkite.lightningserver.sessions.proofs.oauth.OauthClientSecret
import com.lightningkite.lightningserver.sessions.token.JwtTokenFormat
import com.lightningkite.lightningserver.sessions.token.PrivateTinyTokenFormat
import com.lightningkite.lightningserver.sessions.token.TokenFormat
import com.lightningkite.lightningserver.typed.access
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.*
import io.ktor.http.Parameters
import io.ktor.http.Url
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import org.junit.Test
import java.net.URLEncoder
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class OpenIdProviderTest {

    @Serializable
    data class OUser(override val _id: Uuid = Uuid.random(), val email: String = "") : HasId<Uuid> {
        companion object : PrincipalType<OUser, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<OUser> = serializer()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): OUser = OUser(id, "user@example.com")
        }
    }

    class OSessions(database: Runtime<Database>, tokenFormat: TokenFormat) : SessionManager<OUser, Uuid>(
        principal = OUser,
        database = database,
        tokenFormat = Runtime { tokenFormat },
    ) {
        context(server: ServerRuntime)
        override suspend fun sessionExpiration(subject: OUser): Instant? = now() + 30.days

        context(server: ServerRuntime)
        override suspend fun sessionStaleAfter(subject: OUser): Duration? = null
    }

    private val signingKey = OidcSigningKey.generate()

    inner class Fixture(
        resources: List<ProtectedResource> = listOf(ProtectedResource("/mcp", setOf("mcp"), "Test MCP")),
        tokenFormat: TokenFormat = PrivateTinyTokenFormat(),
    ) : ServerBuilder() {
        init { registerBasicMediaTypeCoders() }

        val database = setting("database", Database.Settings("ram"))
        val cache = setting("cache", Cache.Settings("ram"))
        val sessions = path.path("auth") include OSessions(database, tokenFormat)
        val oauthClients = path.path("oauth-clients") include OauthClientEndpoints(database, maintainPermissions = OUser.require())
        val provider = path include OpenIdProviderEndpoints(
            sessions = sessions,
            database = database,
            cache = cache,
            signingKey = Runtime { signingKey },
            getUserClaims = { IdTokenClaims(iss = "", sub = it._id.toString(), aud = "", exp = 0, iat = 0, email = it.email) },
            authorizationUiUrl = Runtime { "https://app.example.com/authorize" },
            resources = resources,
        )
        val mcp = path.path("mcp").post bind BearerChallenge("mcp", HttpHandler { request ->
            request.access(OUser.require(RequiredScope("mcp")))
            HttpResponse.plainText("ok")
        })
    }

    private val user = Uuid.random()
    private val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val challenge = s256(verifier)

    private fun authRequest(
        clientId: String = CLAUDE,
        redirect: String = CALLBACK,
        scope: String = "mcp",
        resource: String? = MCP_RESOURCE,
        codeChallenge: String? = challenge,
        method: String? = "S256",
        nonce: String? = null,
    ) = AuthorizationRequest(
        response_type = "code",
        client_id = clientId,
        redirect_uri = redirect,
        scope = scope,
        state = "xyz",
        nonce = nonce,
        code_challenge = codeChallenge,
        code_challenge_method = method,
        resource = resource,
    )

    context(test: TestRunner<*>)
    private suspend fun Fixture.addClient(
        id: String = CLAUDE,
        scopes: Set<String> = setOf("mcp"),
        redirects: Set<String> = setOf(CALLBACK, "http://127.0.0.1/callback", "http://localhost/callback"),
        secret: String? = null,
        allowRefreshTokens: Boolean = true,
    ) = execute {
        database().table<OauthClient>().insertOne(
            OauthClient(
                _id = id,
                niceName = id,
                scopes = scopes,
                redirectUris = redirects,
                allowRefreshTokens = allowRefreshTokens,
                secrets = secret?.let { setOf(OauthClientSecret(createdAt = now(), masked = "x", secretHash = it.fastHash())) } ?: setOf(),
            )
        )
    }

    context(test: TestRunner<*>)
    private fun userAuth(scope: String = "*"): Authentication<OUser> =
        Authentication(principalType = OUser, id = user, sessionId = null, sessionIssuedAt = now(), scopes = setOf(GrantedScope(scope)))

    context(test: TestRunner<*>)
    private suspend fun Fixture.approve(request: AuthorizationRequest = authRequest()): Parameters {
        val redirect = provider.authorizeApprove.test(userAuth(), AuthorizeApproveRequest(request, request.scope.split(' ').toSet())).redirectUri
        return Url(redirect).parameters
    }

    context(test: TestRunner<*>)
    private suspend fun Fixture.code(request: AuthorizationRequest = authRequest()): String = approve(request)["code"]!!

    /** Posts a form like a real client, through routing and body parsing. */
    context(test: TestRunner<*>)
    private suspend fun post(
        handler: HttpHandler<PathSpec0>,
        vararg form: Pair<String, String?>,
        headers: HttpHeaders = HttpHeaders.EMPTY,
    ): Pair<HttpResponse, JsonObject> {
        val body = form.filter { it.second != null }
            .joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, Charsets.UTF_8) }
        val response = handler.test(headers = headers, body = TypedData.text(body, MediaType.Application.FormUrlEncoded))
        return response to Json.parseToJsonElement(response.body!!.text()).jsonObject
    }

    context(test: TestRunner<*>)
    private suspend fun Fixture.redeem(code: String, vararg extra: Pair<String, String?>) = post(
        provider.token,
        "grant_type" to "authorization_code", "code" to code, "redirect_uri" to CALLBACK,
        "client_id" to CLAUDE, "code_verifier" to verifier, *extra,
    )

    context(test: TestRunner<*>)
    private suspend fun Fixture.refresh(token: String, vararg extra: Pair<String, String?>) = post(
        provider.token, "grant_type" to "refresh_token", "refresh_token" to token, "client_id" to CLAUDE, *extra,
    )

    context(test: TestRunner<*>)
    private suspend fun Fixture.callMcp(token: String?): HttpResponse = mcp.test(
        headers = token?.let { HttpHeaders { add(HttpHeader.Authorization, "Bearer $it") } } ?: HttpHeaders.EMPTY,
    )

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    /** An access token of an ordinary first-party session, as the app's own frontend holds. */
    context(test: TestRunner<*>)
    private suspend fun Fixture.rootAccessToken(): String = execute {
        val (session, _) = sessions.newSession(subjectId = user)
        sessions.tokenFormat().create(OUser, with(sessions) { session.toAuth() })
    }

    context(test: TestRunner<*>)
    private suspend fun Fixture.introspect(token: String?) =
        post(provider.introspect, "token" to token, "client_id" to CLAUDE).second["active"]?.jsonPrimitive?.boolean

    // ----- the MCP flow -----------------------------------------------------------------------------------------

    @Test
    fun `MCP client discovers, authorizes, calls, and refreshes`() = runBlocking {
        val server = Fixture()
        server.test({}) {
            server.addClient()
            val publicUrl = execute { generalSettings().publicUrl }

            // 1. No token: a 401 that points at the resource metadata.
            val challenge = server.callMcp(null)
            assertEquals(HttpStatus.Unauthorized, challenge.status)
            assertEquals(
                "Bearer resource_metadata=\"$publicUrl/.well-known/oauth-protected-resource/mcp\", scope=\"mcp\"",
                challenge.headers[HttpHeader.WWWAuthenticate]?.root,
            )

            // 2. Resource metadata, then authorization server metadata at both well-known paths.
            val prm = Json.parseToJsonElement(server.provider.protectedResourceMetadata.test(trailingWildcard = PathSegments(listOf("mcp"))).body!!.text()).jsonObject
            assertEquals("$publicUrl/mcp", prm.string("resource"))
            assertEquals(listOf(publicUrl), prm["authorization_servers"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("mcp"), prm["scopes_supported"]!!.jsonArray.map { it.jsonPrimitive.content })
            for (metadata in listOf(server.provider.authorizationServerMetadata, server.provider.openIdConfiguration)) {
                val doc = metadata.test(null, Unit)
                assertEquals(publicUrl, doc.issuer)
                assertEquals(listOf("S256"), doc.code_challenge_methods_supported)
                assertEquals("https://app.example.com/authorize", doc.authorization_endpoint)
                assertEquals("$publicUrl/oauth/token", doc.token_endpoint)
                assertTrue(doc.authorization_response_iss_parameter_supported)
                assertTrue("none" in doc.token_endpoint_auth_methods_supported)
            }

            // 3. Consent, then the redirect carries code, state and iss.
            val consent = server.provider.authorizePrepare.test(userAuth(), authRequest()).consent!!
            assertEquals(CALLBACK, consent.redirectUri)
            assertEquals(setOf("mcp"), consent.requestedScopes)
            val params = server.approve()
            assertEquals("xyz", params["state"])
            assertEquals(publicUrl, params["iss"])

            // 4. Token exchange over a real form post.
            val (response, tokens) = server.redeem(params["code"]!!, "resource" to MCP_RESOURCE)
            assertEquals(HttpStatus.OK, response.status, tokens.toString())
            assertEquals("no-store", response.headers[HttpHeader.CacheControl]?.root)
            assertEquals("mcp", tokens.string("scope"))
            assertNull(tokens["id_token"], "No ID token without openid")
            val access = tokens.string("access_token")!!
            val refresh1 = tokens.string("refresh_token")!!

            // 5. The access token is bound to the MCP server and works there; the refresh token is not a bearer token.
            assertEquals("$publicUrl/mcp", execute { server.sessions.tokenFormat().read(OUser, access) }!!.audience)
            assertEquals(HttpStatus.OK, server.callMcp(access).status)
            assertEquals(HttpStatus.Unauthorized, server.callMcp(refresh1).status)

            // 6. Refreshing rotates the refresh token.
            val (refreshed, second) = server.refresh(refresh1, "resource" to MCP_RESOURCE)
            assertEquals(HttpStatus.OK, refreshed.status, second.toString())
            val refresh2 = second.string("refresh_token")!!
            assertNotEquals(refresh1, refresh2)
            assertEquals(HttpStatus.OK, server.callMcp(second.string("access_token")).status)
        }
    }

    // ----- refresh token rotation, shared with SessionManager.rotateRefreshToken ----------------------------------

    private var time = Clock.System.now()
    private val clock = object : Clock {
        override fun now() = time
    }

    @Test
    fun `the refresh_token grant rotates on every use`() = runBlocking {
        val server = Fixture()
        server.test({}, clock = { clock }) {
            server.addClient()
            val refresh1 = server.redeem(server.code()).second.string("refresh_token")!!
            time += 61.seconds
            val refresh2 = server.refresh(refresh1).second.string("refresh_token")!!
            time += 61.seconds
            val (response, third) = server.refresh(refresh2)
            assertEquals(HttpStatus.OK, response.status, third.toString())
            assertEquals(3, setOf(refresh1, refresh2, third.string("refresh_token")!!).size)
        }
    }

    @Test
    fun `the previous refresh token within the grace gets the current one back`() = runBlocking {
        val server = Fixture()
        server.test({}, clock = { clock }) {
            server.addClient()
            val refresh1 = server.redeem(server.code()).second.string("refresh_token")!!
            val refresh2 = server.refresh(refresh1).second.string("refresh_token")!!
            time += 59.seconds
            val (response, again) = server.refresh(refresh1)
            assertEquals(HttpStatus.OK, response.status, again.toString())
            assertEquals(refresh2, again.string("refresh_token"))
            assertEquals(HttpStatus.OK, server.callMcp(again.string("access_token")).status)
            assertEquals(HttpStatus.OK, server.refresh(refresh2).first.status)
        }
    }

    @Test
    fun `the previous refresh token after the grace ends the session`() = runBlocking {
        val server = Fixture()
        server.test({}, clock = { clock }) {
            server.addClient()
            val tokens = server.redeem(server.code()).second
            val refresh1 = tokens.string("refresh_token")!!
            val refresh2 = server.refresh(refresh1).second.string("refresh_token")!!
            time += 60.seconds
            assertEquals("invalid_grant", server.refresh(refresh1).second.string("error"))
            // The whole session ends, so the thief's copy and the current token both die.
            assertEquals("invalid_grant", server.refresh(refresh2).second.string("error"))
            val introspected = post(server.provider.introspect, "token" to tokens.string("access_token"), "client_id" to CLAUDE).second
            assertEquals(false, introspected["active"]?.jsonPrimitive?.boolean)
        }
    }

    @Test
    fun `a refresh token presented by another client is refused without rotating or ending its session`() = runBlocking {
        val server = Fixture()
        server.test({}) {
            server.addClient()
            server.addClient(id = "other")
            val refresh = server.redeem(server.code()).second.string("refresh_token")!!
            val (_, refused) = post(server.provider.token, "grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to "other")
            assertEquals("invalid_grant", refused.string("error"))
            assertEquals(HttpStatus.OK, server.refresh(refresh).first.status)
        }
    }

    @Test
    fun `consent is remembered for an https redirect`() = runBlocking {
        val server = Fixture()
        server.test({}) {
            server.addClient()
            server.approve()
            val redirect = server.provider.authorizePrepare.test(userAuth(), authRequest()).redirectUri
            assertNotNull(Url(assertNotNull(redirect)).parameters["code"])
        }
    }

    @Test
    fun `saved consent never skips the prompt for a loopback or custom-scheme redirect`() = runBlocking {
        val server = Fixture()
        server.test({}) {
            server.addClient(redirects = setOf(CALLBACK, "http://127.0.0.1/callback", "http://localhost:3118/callback", "com.example.app:/oauth"))
            server.approve(authRequest(redirect = "http://127.0.0.1:53682/callback"))
            server.approve()
            // Any local process could be listening on these, or have claimed the scheme.
            for (redirect in listOf("http://127.0.0.1:40000/callback", "http://127.0.0.1/callback", "http://localhost:3118/callback", "com.example.app:/oauth")) {
                val prepared = server.provider.authorizePrepare.test(userAuth(), authRequest(redirect = redirect))
                assertNull(prepared.redirectUri, redirect)
                assertEquals(redirect, prepared.consent?.redirectUri)
            }
        }
    }

    @Test
    fun `an approval replaces the client's consent`() = runBlocking {
        val server = Fixture(resources = listOf(ProtectedResource("/mcp", setOf("mcp", "mcp-admin"))))
        server.test({}) {
            server.addClient(scopes = setOf("mcp", "mcp-admin"))
            server.approve(authRequest(scope = "mcp mcp-admin"))
            server.approve(authRequest(scope = "mcp"))
            val saved = execute { server.database().table<UserConsent>().find(Condition.Always).toList() }
            assertEquals(listOf(setOf("mcp")), saved.map { it.scopes })
            // The scope left out of the last approval is asked for again.
            assertNotNull(server.provider.authorizePrepare.test(userAuth(), authRequest(scope = "mcp mcp-admin")).consent)
        }
    }

    @Test
    fun `withdrawing consent ends the client's sessions and asks again`() = runBlocking {
        val server = Fixture()
        server.test({}) {
            server.addClient()
            server.addClient(id = "other")
            val tokens = server.redeem(server.code()).second
            val otherCode = server.code(authRequest(clientId = "other"))
            val (_, otherTokens) = post(
                server.provider.token, "grant_type" to "authorization_code", "code" to otherCode,
                "redirect_uri" to CALLBACK, "client_id" to "other", "code_verifier" to verifier,
            )
            val first = server.rootAccessToken()

            assertEquals(setOf(CLAUDE, "other"), server.provider.consentList.test(userAuth(), Unit).map { it.clientId }.toSet())
            server.provider.withdrawConsent.test(CLAUDE, userAuth(), Unit)

            assertEquals(listOf("other"), server.provider.consentList.test(userAuth(), Unit).map { it.clientId })
            assertEquals("invalid_grant", server.refresh(tokens.string("refresh_token")!!).second.string("error"))
            assertEquals(false, server.introspect(tokens.string("access_token")))
            assertNotNull(server.provider.authorizePrepare.test(userAuth(), authRequest()).consent)
            // Other clients' sessions, and the user's own, are untouched.
            assertEquals(true, post(server.provider.introspect, "token" to otherTokens.string("access_token"), "client_id" to "other").second["active"]?.jsonPrimitive?.boolean)
            assertNotNull(execute { server.sessions.tokenFormat().read(OUser, first) })
            val live = execute { server.sessions.sessionInfo.table().find(Condition.Always).toList().filter { it.terminated == null } }
            assertEquals(setOf(null, "other"), live.map { it.oauthClient }.toSet())
        }
    }

    @Test
    fun `an MCP-scoped token can't list or withdraw consents`() = runBlocking {
        val server = Fixture()
        server.test({}) {
            server.addClient()
            assertFails { server.provider.consentList.test(userAuth("mcp"), Unit) }
            assertFails { server.provider.withdrawConsent.test(CLAUDE, userAuth("mcp"), Unit) }
        }
    }

    @Test
    fun `deny redirects only to a registered redirect`() = runBlocking {
        val server = Fixture()
        server.test({}) {
            server.addClient()
            val params = Url(server.provider.authorizeDeny.test(userAuth(), authRequest()).redirectUri).parameters
            assertEquals("access_denied", params["error"])
            assertEquals("xyz", params["state"])
            val e = assertFailsWith<BadRequestException> {
                server.provider.authorizeDeny.test(userAuth(), authRequest(redirect = "https://evil.example.com/cb"))
            }
            assertEquals("invalid_client", e.detail)
        }
    }

    @Test
    fun `an MCP-scoped token can't approve another authorization`() = runBlocking {
        val server = Fixture()
        server.test({}) {
            server.addClient()
            assertFails { server.provider.authorizeApprove.test(userAuth("mcp"), AuthorizeApproveRequest(authRequest(), setOf("mcp"))) }
        }
    }

    // ----- redirect URIs ----------------------------------------------------------------------------------------

    @Test
    fun `redirect URIs match exactly except a loopback port`() {
        assertTrue(redirectUriMatches(CALLBACK, CALLBACK))
        assertTrue(redirectUriMatches("http://127.0.0.1/callback", "http://127.0.0.1:53682/callback"))
        assertTrue(redirectUriMatches("http://localhost/callback", "http://localhost:3118/callback"))
        assertTrue(redirectUriMatches("http://[::1]/callback", "http://[::1]:3118/callback"))

        assertFalse(redirectUriMatches(CALLBACK, "https://claude.ai/api/mcp/auth_callback/extra"))
        assertFalse(redirectUriMatches(CALLBACK, "https://claude.ai:8443/api/mcp/auth_callback"))
        assertFalse(redirectUriMatches(CALLBACK, "$CALLBACK?next=https://evil.example.com"))
        assertFalse(redirectUriMatches("https://app.example.com/cb", "https://app.example.com.evil.com/cb"))
        assertFalse(redirectUriMatches("http://localhost/callback", "http://127.0.0.1:3118/callback"))
        assertFalse(redirectUriMatches("http://localhost/callback", "http://localhost:3118/other"))
        assertFalse(redirectUriMatches("http://localhost/callback", "https://localhost:3118/callback"))
        assertFalse(redirectUriMatches("http://localhost/callback", "http://localhost:3118@evil.com/callback"))
        assertFalse(redirectUriMatches("http://localhost/callback", "http://localhost:3118/callback#frag"))
        assertFalse(redirectUriMatches("http://localhost/callback", "http://localhost:3118/callback?x=1"))
        assertFalse(redirectUriMatches("http://localhost/callback", "http://evil.com:3118/callback"))
    }

    @Test
    fun `resource URIs ignore case only in scheme and host`() {
        assertTrue(sameResource("https://app.example.com/mcp", "HTTPS://App.Example.com/mcp"))
        assertFalse(sameResource("https://app.example.com/mcp", "https://app.example.com/MCP"))
        assertFalse(sameResource("https://app.example.com/mcp", "https://app.example.com/mcp#x"))
        assertFalse(sameResource("https://app.example.com/mcp", "https://app.example.com:444/mcp"))
        assertFalse(sameResource("https://app.example.com/mcp", "https://evil.com/mcp"))
    }

    @Test
    fun `loopback redirect on another port completes the flow`() = runBlocking {
        val server = Fixture()
        server.test({}) {
            server.addClient()
            val redirect = "http://127.0.0.1:53682/callback"
            val code = server.code(authRequest(redirect = redirect))
            val (response, body) = post(
                server.provider.token, "grant_type" to "authorization_code", "code" to code,
                "redirect_uri" to redirect, "client_id" to CLAUDE, "code_verifier" to verifier,
            )
            assertEquals(HttpStatus.OK, response.status, body.toString())
        }
    }

    @Test
    fun `unknown clients and unregistered redirects are refused without a redirect`() = runBlocking {
        val server = Fixture()
        server.test({}) {
            server.addClient()
            for (request in listOf(authRequest(clientId = "nobody"), authRequest(redirect = "https://evil.example.com/cb"))) {
                val e = assertFailsWith<BadRequestException> { server.provider.authorizePrepare.test(userAuth(), request) }
                assertEquals("invalid_client", e.detail)
            }
        }
    }

    // ----- PKCE and codes ---------------------------------------------------------------------------------------

    @Test
    fun `PKCE S256 is required`() = runBlocking {
        val server = Fixture()
        server.test({}) {
            server.addClient()
            for (request in listOf(authRequest(codeChallenge = null, method = null), authRequest(method = "plain"), authRequest(codeChallenge = "short"))) {
                val e = assertFailsWith<BadRequestException> { server.provider.authorizePrepare.test(userAuth(), request) }
                assertEquals("invalid_request", e.detail)
            }
            assertEquals("invalid_grant", server.redeem(server.code(), "code_verifier" to "x".repeat(43)).second.string("error"))
            val (_, missing) = post(
                server.provider.token, "grant_type" to "authorization_code", "code" to server.code(),
                "redirect_uri" to CALLBACK, "client_id" to CLAUDE,
            )
            assertEquals("invalid_grant", missing.string("error"))
        }
    }

    @Test
    fun `a code works once, for its client and redirect`() = runBlocking {
        val server = Fixture()
        server.test({}) {
            server.addClient()
            server.addClient(id = "other")
            val code = server.code()
            assertEquals(HttpStatus.OK, server.redeem(code).first.status)
            assertEquals("invalid_grant", server.redeem(code).second.string("error"))

            assertEquals("invalid_grant", server.redeem(server.code(), "client_id" to "other").second.string("error"))
            assertEquals("invalid_grant", server.redeem(server.code(), "redirect_uri" to "http://localhost/callback").second.string("error"))

            // A failed redemption burns the code.
            val burned = server.code()
            server.redeem(burned, "code_verifier" to "y".repeat(43))
            assertEquals("invalid_grant", server.redeem(burned).second.string("error"))
        }
    }

    // ----- resources and scopes ---------------------------------------------------------------------------------

    @Test
    fun `tokens are bound to their resource`() = runBlocking {
        val server = Fixture(
            resources = listOf(ProtectedResource("/mcp", setOf("mcp")), ProtectedResource("/reports", setOf("reports")))
        )
        server.test({}) {
            server.addClient(scopes = setOf("mcp", "reports", OpenIdScopes.OPENID))
            val publicUrl = execute { generalSettings().publicUrl }

            val unknown = assertFailsWith<BadRequestException> {
                server.provider.authorizePrepare.test(userAuth(), authRequest(resource = "https://evil.example.com/mcp"))
            }
            assertEquals("invalid_target", unknown.detail)

            // Scopes of another resource, or beyond the resource's, aren't granted.
            for (scope in listOf("reports", "mcp reports", "mcp openid")) {
                val e = assertFailsWith<BadRequestException> {
                    server.provider.authorizePrepare.test(userAuth(), authRequest(scope = scope))
                }
                assertEquals("invalid_scope", e.detail, scope)
            }

            // No scope means the resource's scopes; offline_access is ignored.
            assertEquals(setOf("mcp"), server.provider.authorizePrepare.test(userAuth(), authRequest(scope = "")).consent!!.requestedScopes)
            assertEquals(setOf("mcp"), server.provider.authorizePrepare.test(userAuth(), authRequest(scope = "mcp offline_access")).consent!!.requestedScopes)

            assertEquals("invalid_target", server.redeem(server.code(), "resource" to "$publicUrl/reports").second.string("error"))

            val refresh = server.redeem(server.code()).second.string("refresh_token")!!
            assertEquals("invalid_target", server.refresh(refresh, "resource" to "$publicUrl/reports").second.string("error"))
        }
    }

    @Test
    fun `resources may not share scopes`() {
        assertFailsWith<IllegalArgumentException> {
            Fixture(resources = listOf(ProtectedResource("/a", setOf("mcp")), ProtectedResource("/b", setOf("mcp"))))
        }
    }

    @Test
    fun `a token for the resource without its scope gets insufficient_scope, a bad one invalid_token`() = runBlocking {
        val server = Fixture(resources = listOf(ProtectedResource("/mcp", setOf("mcp", "mcp-read"))))
        server.test({}) {
            server.addClient(scopes = setOf("mcp", "mcp-read"))
            val tokens = server.redeem(server.code(authRequest(scope = "mcp-read"))).second
            val forbidden = server.callMcp(tokens.string("access_token"))
            assertEquals(HttpStatus.Forbidden, forbidden.status)
            assertTrue(forbidden.headers[HttpHeader.WWWAuthenticate]!!.root.startsWith("Bearer error=\"insufficient_scope\""))

            val invalid = server.callMcp("refresh/OUser/${Uuid.random()}:nope")
            assertEquals(HttpStatus.Unauthorized, invalid.status)
            assertTrue(invalid.headers[HttpHeader.WWWAuthenticate]!!.root.startsWith("Bearer error=\"invalid_token\""))

            // Unreadable tokens count as none, which still gets the challenge.
            val garbage = server.callMcp("not-a-token")
            assertEquals(HttpStatus.Unauthorized, garbage.status)
            assertTrue(garbage.headers[HttpHeader.WWWAuthenticate]!!.root.startsWith("Bearer resource_metadata="))
        }
    }

    private fun assertInvalidToken(response: HttpResponse) {
        assertEquals(HttpStatus.Unauthorized, response.status)
        assertTrue(response.headers[HttpHeader.WWWAuthenticate]!!.root.startsWith("Bearer error=\"invalid_token\", resource_metadata="))
    }

    @Test
    fun `the app's own root token is refused at the resource`() = runBlocking {
        val server = Fixture()
        server.test({}) {
            assertInvalidToken(server.callMcp(server.rootAccessToken()))
        }
    }

    @Test
    fun `a token with the resource's scope but not issued for it is refused`() = runBlocking {
        val server = Fixture()
        server.test({}) {
            server.addClient()
            val tokens = server.redeem(server.code(authRequest(resource = null))).second
            assertEquals("mcp", tokens.string("scope"))
            assertInvalidToken(server.callMcp(tokens.string("access_token")))
        }
    }

    @Test
    fun `a token for another resource is refused`() = runBlocking {
        val server = Fixture(resources = listOf(ProtectedResource("/mcp", setOf("mcp")), ProtectedResource("/reports", setOf("reports"))))
        server.test({}) {
            server.addClient(scopes = setOf("mcp", "reports"))
            val publicUrl = execute { generalSettings().publicUrl }
            val tokens = server.redeem(server.code(authRequest(scope = "reports", resource = "$publicUrl/reports"))).second
            assertInvalidToken(server.callMcp(tokens.string("access_token")))
        }
    }

    @Test
    fun `with JWT access tokens, the resource token is accepted and the root token refused`() = runBlocking {
        val server = Fixture(tokenFormat = JwtTokenFormat())
        server.test({}) {
            server.addClient()
            val tokens = server.redeem(server.code()).second
            assertEquals(HttpStatus.OK, server.callMcp(tokens.string("access_token")).status)
            val refreshed = server.refresh(tokens.string("refresh_token")!!).second
            assertEquals(HttpStatus.OK, server.callMcp(refreshed.string("access_token")).status)
            assertInvalidToken(server.callMcp(server.rootAccessToken()))
        }
    }

    // ----- clients and errors -----------------------------------------------------------------------------------

    @Test
    fun `token endpoint errors are OAuth-shaped and leak nothing`() = runBlocking {
        val server = Fixture()
        server.test({}) {
            server.addClient()
            val (badGrant, badGrantBody) = post(server.provider.token, "grant_type" to "password", "client_id" to CLAUDE)
            assertEquals(HttpStatus.BadRequest, badGrant.status)
            assertEquals("unsupported_grant_type", badGrantBody.string("error"))

            val (malformed, malformedBody) = post(server.provider.token, "client_id" to CLAUDE)
            assertEquals(HttpStatus.BadRequest, malformed.status)
            assertEquals("invalid_request", malformedBody.string("error"))
            assertFalse(malformedBody.toString().contains("com.lightningkite"), malformedBody.toString())

            val (unknown, unknownBody) = post(server.provider.token, "grant_type" to "authorization_code", "client_id" to "nobody", "code" to "x")
            assertEquals(HttpStatus.Unauthorized, unknown.status)
            assertEquals("invalid_client", unknownBody.string("error"))
        }
    }

    @Test
    fun `a confidential client must present its secret, in the body or by Basic`() = runBlocking {
        val server = Fixture()
        server.test({}) {
            server.addClient(secret = "s3cret/with+chars")
            assertEquals("invalid_client", server.redeem(server.code()).second.string("error"))
            assertEquals("invalid_client", server.redeem(server.code(), "client_secret" to "wrong").second.string("error"))
            assertEquals(HttpStatus.OK, server.redeem(server.code(), "client_secret" to "s3cret/with+chars").first.status)

            val basic = Base64.encode("$CLAUDE:${URLEncoder.encode("s3cret/with+chars", Charsets.UTF_8)}".encodeToByteArray())
            val (response, body) = post(
                server.provider.token, "grant_type" to "authorization_code", "code" to server.code(),
                "redirect_uri" to CALLBACK, "code_verifier" to verifier,
                headers = HttpHeaders { add(HttpHeader.Authorization, "Basic $basic") },
            )
            assertEquals(HttpStatus.OK, response.status, body.toString())
        }
    }

    @Test
    fun `a client without refresh tokens gets none`() = runBlocking {
        val server = Fixture()
        server.test({}) {
            server.addClient(allowRefreshTokens = false)
            assertNull(server.redeem(server.code()).second["refresh_token"])
        }
    }

    @Test
    fun `revoke ends the session and introspect reports it`() = runBlocking {
        val server = Fixture()
        server.test({}) {
            server.addClient()
            server.addClient(id = "other")
            val tokens = server.redeem(server.code()).second
            val access = tokens.string("access_token")!!
            val refresh = tokens.string("refresh_token")!!

            val active = post(server.provider.introspect, "token" to access, "client_id" to CLAUDE).second
            assertEquals(true, active["active"]?.jsonPrimitive?.boolean)
            assertEquals("mcp", active.string("scope"))
            assertEquals(false, post(server.provider.introspect, "token" to access, "client_id" to "other").second["active"]?.jsonPrimitive?.boolean)

            // Another client can't revoke it.
            assertEquals(HttpStatus.OK, post(server.provider.revoke, "token" to refresh, "client_id" to "other").first.status)
            assertEquals(true, post(server.provider.introspect, "token" to refresh, "client_id" to CLAUDE).second["active"]?.jsonPrimitive?.boolean)

            assertEquals(HttpStatus.OK, post(server.provider.revoke, "token" to refresh, "client_id" to CLAUDE).first.status)
            assertEquals(false, post(server.provider.introspect, "token" to access, "client_id" to CLAUDE).second["active"]?.jsonPrimitive?.boolean)
            assertEquals("invalid_grant", server.refresh(refresh).second.string("error"))
            // Ending a session is not withdrawing consent: clients revoke on their own logout.
            assertNotNull(server.provider.authorizePrepare.test(userAuth(), authRequest()).redirectUri)
        }
    }

    @Test
    fun `the previous refresh token within the grace is the session's for introspect and revoke`() = runBlocking {
        val server = Fixture()
        server.test({}, clock = { clock }) {
            server.addClient()
            val refresh1 = server.redeem(server.code()).second.string("refresh_token")!!
            val refresh2 = server.refresh(refresh1).second.string("refresh_token")!!
            time += 59.seconds
            assertEquals(true, server.introspect(refresh1))
            // Introspecting it is not using it, so nothing ends.
            assertEquals(true, server.introspect(refresh2))

            assertEquals(HttpStatus.OK, post(server.provider.revoke, "token" to refresh1, "client_id" to CLAUDE).first.status)
            assertEquals(false, server.introspect(refresh2))
            assertEquals("invalid_grant", server.refresh(refresh2).second.string("error"))
        }
    }

    @Test
    fun `the previous refresh token after the grace is unknown to introspect and revoke`() = runBlocking {
        val server = Fixture()
        server.test({}, clock = { clock }) {
            server.addClient()
            val refresh1 = server.redeem(server.code()).second.string("refresh_token")!!
            val refresh2 = server.refresh(refresh1).second.string("refresh_token")!!
            time += 61.seconds
            assertEquals(false, server.introspect(refresh1))
            post(server.provider.revoke, "token" to refresh1, "client_id" to CLAUDE)
            assertEquals(true, server.introspect(refresh2))
        }
    }

    // ----- client registry --------------------------------------------------------------------------------------

    @OptIn(com.lightningkite.lightningserver.EngineApi::class)
    @Test
    fun `the client registry's REST endpoints are served, to maintainers only`() = runBlocking {
        val server = Fixture()
        server.test({}) {
            server.addClient()
            suspend fun get(token: String?) = engine.handleRoot(
                HttpRequest(
                    path = RawHttpEndpoint(asString = "/oauth-clients", method = HttpMethod.GET),
                    queryParameters = QueryParameters.EMPTY,
                    headers = token?.let { HttpHeaders { add(HttpHeader.Authorization, "Bearer $it") } } ?: HttpHeaders.EMPTY,
                    domain = "example.com",
                    protocol = "https",
                    sourceIp = "203.0.113.7",
                ),
                Execution.ID.generate(),
            )
            val listed = get(server.rootAccessToken())
            val body = listed.body!!.text()
            assertEquals(HttpStatus.OK, listed.status, body)
            assertTrue("\"$CLAUDE\"" in body, body)
            assertTrue(get(null).status in setOf(HttpStatus.Unauthorized, HttpStatus.Forbidden))
        }
    }

    @Test
    fun `an OAuth refresh token is refused by the session manager's own endpoints`() = runBlocking {
        val server = Fixture()
        server.test({}) {
            server.addClient()
            val refresh = server.redeem(server.code()).second.string("refresh_token")!!
            assertFailsWith<UnauthorizedException> { server.sessions.tokenSimple.test(null, refresh) }
        }
    }

    // ----- OpenID Connect ---------------------------------------------------------------------------------------

    @Test
    fun `openid gets a signed ID token and userinfo`() = runBlocking {
        val server = Fixture()
        server.test({}) {
            server.addClient(id = "oidc", scopes = setOf(OpenIdScopes.OPENID, OpenIdScopes.EMAIL))
            val code = server.code(authRequest(clientId = "oidc", scope = "openid email", resource = null, nonce = "n-1"))
            val (_, tokens) = post(
                server.provider.token, "grant_type" to "authorization_code", "code" to code,
                "redirect_uri" to CALLBACK, "client_id" to "oidc", "code_verifier" to verifier,
            )
            val idToken = tokens.string("id_token")!!
            val (header, payload, signature) = idToken.split('.')
            val urlDecoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            val claims = Json.parseToJsonElement(urlDecoder.decode(payload).decodeToString()).jsonObject
            assertEquals("oidc", claims.string("aud"))
            assertEquals("n-1", claims.string("nonce"))
            assertEquals(execute { generalSettings().publicUrl }, claims.string("iss"))
            // A relying party verifies with the published JWK alone, using the JDK.
            val jwk = server.provider.jwks.test(null, Unit).keys.single()
            assertEquals("RS256", jwk.alg)
            assertEquals(jwk.kid, Json.parseToJsonElement(urlDecoder.decode(header).decodeToString()).jsonObject.string("kid"))
            val publicKey = java.security.KeyFactory.getInstance("RSA").generatePublic(
                java.security.spec.RSAPublicKeySpec(
                    java.math.BigInteger(1, urlDecoder.decode(jwk.n!!)),
                    java.math.BigInteger(1, urlDecoder.decode(jwk.e!!)),
                )
            )
            val jdkVerifier = java.security.Signature.getInstance("SHA256withRSA").apply {
                initVerify(publicKey)
                update("$header.$payload".encodeToByteArray())
            }
            assertTrue(jdkVerifier.verify(urlDecoder.decode(signature)))

            val info = server.provider.userinfo.test(
                Authentication(principalType = OUser, id = user, sessionId = null, sessionIssuedAt = now(), scopes = setOf(GrantedScope("openid"), GrantedScope("email"))),
                Unit,
            )
            assertEquals("user@example.com", info.email)
        }
    }

    companion object {
        const val CLAUDE = "claude"
        const val CALLBACK = "https://claude.ai/api/mcp/auth_callback"
        val MCP_RESOURCE: String get() = "http://localhost:8080/mcp"
    }
}
