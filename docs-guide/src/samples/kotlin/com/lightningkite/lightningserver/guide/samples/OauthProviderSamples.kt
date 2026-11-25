@file:OptIn(com.lightningkite.lightningserver.ExperimentalOauthProviderApi::class)

package com.lightningkite.lightningserver.guide.samples

// region oauth-provider-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.sessions.*
import com.lightningkite.lightningserver.sessions.openid.*
import com.lightningkite.lightningserver.sessions.proofs.oauth.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.cache.*
import com.lightningkite.services.database.*
// endregion oauth-provider-imports
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

// region oauth-provider-server
object McpServer : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())
    // Required: startup fails without it. The generated default only fills in the suggested settings file.
    val oidcSigningKey = setting("oidcSigningKey", OidcSigningKey.generate())

    val auth = path.path("auth") module object : AuthEndpoints<Account, Uuid>(
        principal = AccountAuth,
        database = database,
        cache = cache,
    ) {
        context(server: ServerRuntime)
        override suspend fun requiredProofStrengthFor(subject: Account): Int = 10

        context(server: ServerRuntime)
        override suspend fun sessionExpiration(subject: Account): Instant? = now() + 90.days

        context(server: ServerRuntime)
        override suspend fun sessionStaleAfter(subject: Account): Duration? = 30.days
    }

    val oauthClients = path.path("oauth-clients") module OauthClientEndpoints(database)
    val oauth = path include OpenIdProviderEndpoints(
        sessions = auth,
        database = database,
        cache = cache,
        signingKey = oidcSigningKey,
        getUserClaims = { IdTokenClaims(iss = "", sub = it._id.toString(), aud = "", exp = 0, iat = 0, email = it.email.raw) },
        authorizationUiUrl = Runtime { "https://app.example.com/oauth/authorize" },
        resources = listOf(ProtectedResource(path = "/mcp", scopes = setOf("mcp"), name = "Example MCP")),
    )

    val mcp = path.path("mcp").post bind HttpHandler { request ->
        request.access(AccountAuth.require(RequiredScope("mcp")))
        HttpResponse.plainText("Hello from MCP")
    }

    init {
        registerBasicMediaTypeCoders()
    }
}
// endregion oauth-provider-server

// region oauth-provider-client
val claudeClient = OauthClient(
    _id = "claude",
    niceName = "Claude",
    scopes = setOf("mcp"),
    redirectUris = setOf(
        "https://claude.ai/api/mcp/auth_callback",
        "http://localhost/callback",   // Claude Code; any port matches
    ),
)
// endregion oauth-provider-client

fun oauthProviderDiscoveryTest() = McpServer.testBlocking(settings = {
    database set Database.Settings("ram")
    cache set Cache.Settings("ram")
}) {
    val publicUrl = execute { generalSettings().publicUrl }
    val challenge = mcp.test()
    check(challenge.status == HttpStatus.Unauthorized)
    check(challenge.headers[HttpHeader.WWWAuthenticate]!!.root.contains("$publicUrl/.well-known/oauth-protected-resource/mcp"))
    val metadata = oauth.authorizationServerMetadata.test(null, Unit)
    check(metadata.issuer == publicUrl)
    check(metadata.token_endpoint == "$publicUrl/oauth/token")
}
