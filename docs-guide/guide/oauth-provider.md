# OAuth authorization server

> **Experimental.**  The provider has not been verified against the OAuth and OpenID Connect specifications or with
> real clients, and may change.  Its declarations (and `ProtectedResource`, and the `OauthClient` fields only it reads)
> are marked `@ExperimentalOauthProviderApi`: opt in with `@OptIn(ExperimentalOauthProviderApi::class)` or the
> compiler option `optIn.add("com.lightningkite.lightningserver.ExperimentalOauthProviderApi")`.

`OpenIdProviderEndpoints` (in `sessions-oauth`) makes your server an OAuth 2.1 authorization server and OpenID Connect
provider.  Its main use is letting remote MCP clients (claude.ai custom connectors, Claude Desktop, Claude Code)
connect to your MCP endpoint as the user who approves them.  For the other direction, signing your users in with
Google, see [OAuth](oauth.md).

Tokens belong to ordinary sessions of your `SessionManager` (here, `AuthEndpoints` from
[Proof-based login](proof-session.md)), tagged with the client (`Session.oauthClient`), holding only the granted
scopes, and bound to the requested resource (`Session.audience`).  They show up, and are revoked, like any other
session.

## Wiring

<!-- sample: com/lightningkite/lightningserver/guide/samples/OauthProviderSamples.kt#oauth-provider-imports -->
```kotlin
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
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/OauthProviderSamples.kt#oauth-provider-server -->
```kotlin
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
```

Mount the provider at the server root (`path include`): the issuer is the `publicUrl` from the `general` settings, and
clients look for its metadata at `/.well-known/...`.  `getUserClaims` supplies the identity claims for ID tokens and
userinfo; the provider overwrites `iss`, `aud`, `exp`, `iat`, `nonce` and `auth_time`.

`oidcSigningKey` is a required setting, like `secretBasis`: a settings file without it stops startup with
`IncompleteSettingsException`, and the `settings.suggested.json` written then holds a freshly generated key to copy in.
Keep that key: clients verify ID tokens against the published key set, so a new key makes earlier ID tokens
unverifiable, and every instance must share it.  Only tests use the generated default.

The provider registers each of `resources` in `protectedResources`, so the MCP endpoint needs no wrapper: a request
without a token gets the 401 with
`WWW-Authenticate: Bearer resource_metadata="…/.well-known/oauth-protected-resource/mcp", scope="mcp"` that starts a
client's login.  A token that is invalid, unreadable, or not issued for this resource gets a 401 `error="invalid_token"`;
a token for this resource without the scope gets a 403 `insufficient_scope`.  Every endpoint answers this way (see
[Auth](auth.md)); a protected resource only adds `resource_metadata` and its scopes.

## Audience

A token issued with a `resource` parameter (RFC 8707) is bound to that resource: its `Authentication.audience` is the
resource's URI (the public URL followed by its path, `https://example.com/mcp`), and with `JwtTokenFormat` that is its
`aud` claim (RFC 9068).  A request to a protected resource's path accepts only tokens whose audience is exactly it, so the
app's own tokens, even root ones, and a client's token for another resource or for none are refused there.  MCP
clients always send `resource`; a client that doesn't gets tokens the MCP endpoint refuses.

Elsewhere scopes still do the work: a resource token carries only that resource's scopes, and the rest of the API
requires root.  `JwtTokenFormat` accepts an `aud` that is its `audience` or its `audience` followed by a path, so with
a custom `audience` that isn't the public URL, resource tokens are refused everywhere.

## Clients

Clients are `OauthClient` rows, created by an admin through `OauthClientEndpoints`: the usual REST endpoints at its
path, and `{_id}/create-secret`, all requiring `maintainPermissions` (the superuser by default).  There is no dynamic
registration.
For Claude, one public client per app:

<!-- sample: com/lightningkite/lightningserver/guide/samples/OauthProviderSamples.kt#oauth-provider-client -->
```kotlin
val claudeClient = OauthClient(
    _id = "claude",
    niceName = "Claude",
    scopes = setOf("mcp"),
    redirectUris = setOf(
        "https://claude.ai/api/mcp/auth_callback",
        "http://localhost/callback",   // Claude Code; any port matches
    ),
)
```

The user enters the client ID in the connector's advanced settings.  A client with an active secret (`create-secret`)
must send it, by `client_secret_post` or `client_secret_basic`; one without is public and relies on PKCE.  Set
`allowRefreshTokens = false` for a client that should get only short-lived access tokens, and
`postLogoutRedirectUris` for where `end_session` may send the browser.

## Endpoints

| Path | Purpose |
|---|---|
| `/.well-known/oauth-authorization-server`, `/.well-known/openid-configuration` | Server metadata (RFC 8414) |
| `/.well-known/oauth-protected-resource/{path}` | Resource metadata (RFC 9728) for each `ProtectedResource` |
| `/oauth/authorize/prepare`, `approve`, `deny` | Called by your consent screen, with the user's own token |
| `GET /oauth/consents`, `DELETE /oauth/consents/{clientId}` | The user's consents, and withdrawing one |
| `/oauth/token` | Code and refresh grants; form or JSON bodies; OAuth error bodies |
| `/oauth/revoke`, `/oauth/introspect` | RFC 7009 and RFC 7662 |
| `/oauth/userinfo`, `/oauth/jwks`, `/oauth/end_session` | OpenID Connect |

## The consent screen

The provider renders no UI.  `authorizationUiUrl` is a frontend route that receives the client's query parameters
(`response_type`, `client_id`, `redirect_uri`, `scope`, `state`, `code_challenge`, `code_challenge_method`, `resource`,
`nonce`) and:

1. Logs the user in if needed.
2. Calls `oauth/authorize/prepare` with an `AuthorizationRequest`.  It returns either `redirectUri` (consent was
   already given and the redirect identifies the client; see below), or `consent` to show: the client's name, the
   scopes, and the **host of `redirectUri`**, so a user can spot a client posing as another.
3. Calls `oauth/authorize/approve` or `oauth/authorize/deny` and sends the browser to the returned `redirectUri`.

Never build a redirect yourself.  If `prepare` fails with `invalid_client`, the client or redirect isn't registered:
show an error and don't redirect.  Send the page with `frame-ancestors 'none'`.

A saved consent skips the screen only when the redirect URI exactly matches a registered `https` one, since only that
site can receive the code.  For a loopback redirect (any port), a custom scheme, or plain `http`, the screen is always
shown: any local process can listen on a loopback port and any app can claim a scheme, so such a request may come from
an impersonator of a client the user approved, and a public client can't prove otherwise (RFC 8252 §8.6).

## Consents

Approving stores one `UserConsent` per user and client, replacing the previous one, so a scope the user left out is
asked for again.  `GET oauth/consents` lists the user's consents as `ClientConsent`s (client name, logo, scopes, when);
`DELETE oauth/consents/{clientId}` withdraws one and ends the user's sessions issued to that client, so its refresh
tokens stop working.  Its access tokens last until they expire (five minutes by default) unless the session manager
has `tokenInvalidationChecks`.  Both need a root-scoped user token.

Ending a session, by `oauth/revoke`, `end_session` or the session list, keeps the consent: clients revoke tokens on
their own logout, which shouldn't withdraw the user's approval.

## Refresh tokens

An OAuth session's refresh token rotates from the moment it is issued: each `refresh_token` grant returns a new one,
through the same `SessionManager.rotateRefreshToken` as the
[refresh token cookie](proof-session.md#keeping-a-web-clients-refresh-token-in-a-cookie).  For 60 seconds
after a rotation, the replaced token gets the current one back, in case the client lost the response; after that,
presenting it ends the session, so a stolen copy dies with the original.  Before rotating, the grant checks that the
token was issued to the calling client, that the client may still have its scopes, and that a `resource` parameter
is the one the token was issued for.  Revocation and introspection treat the replaced token within those 60 seconds
as the session's: revoking it ends the session.

Because the session rotates, its refresh token is refused at `auth/token/simple` and as a bearer token, even with
`acceptRefreshTokenAsBearer`: it works only at `/oauth/token`.

## Security

- Redirect URIs match exactly.  A registered loopback URI (`http://127.0.0.1`, `http://[::1]`, `http://localhost`)
  matches on any port, as native clients need (RFC 8252).
- PKCE with S256 is required for every client.
- Codes are random, last five minutes, work once (even under concurrent redemption), and are bound to the client,
  redirect URI, resource and scopes.  A failed redemption burns the code.
- A token for a resource is bound to it (see [Audience](#audience)) and carries only that resource's scopes, and
  resources can't share scopes.
- The authorize endpoints require a root-scoped user token, so an issued token can't approve further grants.
- Token endpoint errors are `{"error", "error_description"}` with no internal details; unexpected failures are
  `server_error`.

There is no rate limit inside the provider: every credential it checks is high-entropy.  Use the server's general
[rate limiting](rate-limiting.md).

## Not supported

Dynamic client registration, Client ID Metadata Documents, and the implicit and client-credentials grants.
