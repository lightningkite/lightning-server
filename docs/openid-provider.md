# OpenID Connect Provider

Last updated June 2026 (`version-5`)

The `sessions-openid-provider` modules turn a Lightning Server application into an **OpenID Connect
(OIDC) provider** — an identity provider that third-party applications can use to offer
"Sign in with [your app]". It implements the OpenID Connect **Authorization Code flow with PKCE**,
plus token refresh, revocation (RFC 7009), introspection (RFC 7662), and RP-initiated logout.

It is built directly on top of your existing [`SessionManager`](sessions.md): the access and refresh
tokens issued to relying parties are ordinary Lightning Server sessions, tagged with the client they
were issued to. The session table is therefore the single source of truth for issuance, validity, and
revocation — there is no parallel token store to keep in sync.

## Modules

- **`sessions-openid-provider-shared`** — multiplatform wire models (shared with clients).
- **`sessions-openid-provider`** — the JVM endpoints (`OpenIdProviderEndpoints`, `OauthClientEndpoints`).

```kotlin
// build.gradle.kts
dependencies {
    api(project(":sessions-openid-provider"))
}
```

## API-only design (no built-in UI)

This provider **does not render any login or consent UI, and does not own a browser redirect
handler**. Your application's frontend owns those — typically the Lightning Server + KiteUI auth
component. The `authorization_endpoint` advertised in discovery is a **route in your frontend**, not
an endpoint in this module.

The flow:

```
Relying Party (RP) ──redirect──▶  https://yourapp.com/authorize?client_id&scope&redirect_uri&state&code_challenge
                                  (a route in YOUR frontend)
   frontend:
     1. ensure the user is logged in (normal SessionManager session)
     2. POST /oauth/authorize/prepare   (Bearer: the user's access token)
            └─▶ { redirectUri }            → done (trusted client or prior consent)
            └─▶ { consent: {...} }         → render a consent screen, then:
     3. POST /oauth/authorize/approve  (Bearer)  ─▶ { redirectUri }
     4. browser ◀─redirect─ redirectUri   (= redirect_uri?code=...&state=...)
        (on denial, redirect to redirect_uri?error=access_denied instead)

RP ──▶ POST /oauth/token       ─▶ { id_token, access_token, refresh_token }   (machine-to-machine)
RP ──▶ GET  /oauth/userinfo    ─▶ { sub, email, ... }                          (Bearer access token)
```

The user's "logged-in at the IdP" state is just their normal `SessionManager` session — its access
token is the Bearer token the frontend sends to `prepare`/`approve`.

## Setup

Wire `OpenIdProviderEndpoints` alongside your existing `SessionManager`/`AuthEndpoints`, and an
`OauthClientEndpoints` for managing client registrations:

```kotlin
import com.lightningkite.lightningserver.sessions.openid.*
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.definition.generalSettings

object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())

    // Your usual authentication (a SessionManager). OpenID composes with this one.
    val auth = path.path("auth") include object : AuthEndpoints<User, Uuid>(
        principal = UserAuth, database = database
    ) { /* sessionExpiration / sessionStaleAfter / requiredProofStrengthFor */ }

    // Admin CRUD for registering client applications (super-user only by default).
    val oauthClients = path.path("oauth-clients") module OauthClientEndpoints(database)

    // The OpenID Connect provider.
    val openId = path.path("oauth") module OpenIdProviderEndpoints(
        sessions = auth,                 // reuse the SessionManager above
        database = database,
        cache = cache,
        signingKey = secretBasis.oidcSigner(),   // see "Signing key" below
        getUserClaims = { user ->
            IdTokenClaims(
                iss = "", sub = user._id.toString(), aud = "", exp = 0, iat = 0,  // iss/aud/exp/iat are set by the provider
                email = user.email,
                email_verified = user.emailVerified,
                name = user.name,
                picture = user.avatarUrl,
            )
        },
        issuerUrl = Runtime { generalSettings().publicUrl },
        oauthBaseUrl = Runtime { generalSettings().publicUrl + "/oauth" },     // must match where this is mounted
        authorizationUiUrl = Runtime { generalSettings().publicUrl + "/authorize" }, // your frontend route
    )
}
```

Important: the provider must **not** be its own separate `SessionManager` for the same principal —
that would double-register the principal. Pass your existing `SessionManager`/`AuthEndpoints`
instance as `sessions`.

### Constructor reference

| Parameter | Meaning |
|---|---|
| `sessions` | Your existing `SessionManager<USER, ID>`; tokens are issued as sessions on it. |
| `database` | Stores `OauthClient`s and `UserConsent`s. |
| `cache` | Holds short-lived, single-use authorization codes. |
| `signingKey` | `RuntimeDeferred<Signer>` that signs ID tokens. See below. |
| `getUserClaims` | Maps your subject to `IdTokenClaims`. `iss`/`aud`/`iat`/`exp`/`nonce`/`auth_time` are overwritten by the provider; you supply identity claims. |
| `issuerUrl` | The `iss` value, normally your public URL. |
| `oauthBaseUrl` | Absolute base URL where these endpoints are mounted; used to build discovery URLs. |
| `authorizationUiUrl` | Absolute URL of your frontend's authorization route (advertised as `authorization_endpoint`). |
| `scopeDescriptions` | Human-readable scope descriptions for the consent screen (defaults provided). |
| `keyId` | JWKS key id (`kid`); default `"default"`. |
| `authorizationCodeLifetime` | Default 10 minutes. |
| `accessTokenLifetime` | Reported as `expires_in`; should match your token format's access-token expiry (default 5 minutes). |

## Signing key

ID tokens are JWTs signed with an asymmetric key whose **public** half is published at the JWKS
endpoint. This key **must be stable across restarts** — if it changes, every previously issued ID
token becomes unverifiable.

The recommended approach derives a deterministic **ES256** (ECDSA P-256) key from your server's
secret basis, so it is persistent by construction with no separate key storage:

```kotlin
signingKey = secretBasis.oidcSigner()            // ES256, derived from the managed secret basis
```

Because an EC private key is just a scalar, it can be derived from the basis; the same basis always
yields the same key and therefore the same JWKS. To rotate, change the derivation variant
(`secretBasis.oidcSigner("oidc-es256-v2")`).

An RSA option is also available (`generateRS256Signer()`), but it generates a *new* key each call,
so it must be persisted yourself (e.g. via a secret source) — prefer the secret-basis ES256 key.

## Registering client applications

Relying parties are stored as `OauthClient` records. `OauthClientEndpoints` exposes standard REST
CRUD plus a secret-minting action; by default management requires a super user
(`maintainPermissions = AuthRequirement.IsSuperUser`).

```kotlin
@Serializable @GenerateDataClassPaths
data class OauthClient(
    val _id: String,                          // the client_id (public)
    val niceName: String,                     // shown on the consent screen
    val logo: String? = null,
    val scopes: Set<String> = setOf(),        // scopes this client may request
    val secrets: Set<OauthClientSecret> = setOf(),    // hashed; empty = public client
    val redirectUris: Set<String> = setOf(),          // exact-match allowlist
    val postLogoutRedirectUris: Set<String> = setOf(),
    val trusted: Boolean = false,             // true = skip the consent screen (first-party)
    val requirePkce: Boolean = false,         // require PKCE even for confidential clients
    val allowRefreshTokens: Boolean = true,   // may receive refresh tokens (offline_access)
)
```

- **Public clients** (no secrets — SPAs, mobile/native apps) **must use PKCE** (`S256`).
- **Confidential clients** (have at least one secret) authenticate at the token endpoint with
  `client_secret` (`client_secret_post`). Set `requirePkce = true` to require PKCE too.
- **Trusted** clients skip the consent screen entirely.
- A client may only request scopes listed in its `scopes`.

### Minting a client secret

`POST /oauth-clients/{_id}/create-secret` returns a new secret **in plaintext exactly once**; only a
salted hash is stored. Multiple active secrets are allowed, so you can rotate without downtime
(create a new one, migrate, then remove the old).

## Endpoints

All endpoints are typed (`ApiHttpHandler`), so they appear in the generated SDK. Paths below assume
the provider is mounted at `/oauth`.

| Method & path | Auth | Purpose |
|---|---|---|
| `GET /.well-known/openid-configuration` | none | Discovery metadata. |
| `GET /oauth/jwks` | none | Public keys (JWK Set). |
| `POST /oauth/authorize/prepare` | user (Bearer) | Validate a request; returns a redirect (with code) or a consent request. |
| `POST /oauth/authorize/approve` | user (Bearer) | Record consent and return the redirect carrying the code. |
| `POST /oauth/token` | client creds in body | Exchange an authorization code or refresh token for tokens. |
| `GET /oauth/userinfo` | access token (Bearer) | Claims for the token, filtered by granted scopes. |
| `POST /oauth/end_session` | access token (Bearer) | Terminate the token's session; validate post-logout redirect. |
| `POST /oauth/revoke` | client creds in body | Revoke a token by terminating its session (RFC 7009). |
| `POST /oauth/introspect` | client creds in body | Report whether a token is active and its metadata (RFC 7662). |

### Authorize: prepare / approve

`authorize/prepare` is authenticated as the **end user** (their normal session token). It validates
`client_id`, `redirect_uri` (exact match + HTTPS, except localhost), `scope` (must include `openid`
and be allowed for the client), `response_type=code`, and PKCE, then returns:

- `{ redirectUri }` — the client is trusted, or the user has already consented to these scopes;
  send the browser there.
- `{ consent }` — render a consent screen from `consent.requestedScopes` /
  `consent.scopeDescriptions`, then call `authorize/approve` with the granted scopes
  (which must include `openid` and be a subset of the requested scopes).

Consent is persisted per `(user, client, scopes)`, so returning users skip the screen.

### Token

```
POST /oauth/token
  grant_type=authorization_code, code, redirect_uri, client_id, [client_secret], [code_verifier]
  grant_type=refresh_token,      refresh_token, client_id, [client_secret]
```

Returns `{ access_token, token_type: "Bearer", expires_in, refresh_token?, id_token, scope }`.
A `refresh_token` is only issued when the client `allowRefreshTokens` and the `offline_access` scope
was granted.

### UserInfo

`GET /oauth/userinfo` with the access token as a Bearer token. Returns the `sub` plus claims allowed
by the granted scopes: `profile` → name/picture/etc., `email` → email/email_verified,
`address` → address, `phone` → phone_number/phone_number_verified.

### Revocation & introspection

Both are client-authenticated and operate on the session behind the token. Revoking an access or
refresh token terminates the session (stopping refresh and future issuance); an already-issued
access token, being self-contained, stays valid until its short expiry. Introspection returns
`{ active: false }` for an invalid/expired/revoked token or one issued to a different client.

### End session (logout)

`POST /oauth/end_session` with the access token terminates that token's session — the same
mechanism as `SessionManager`'s session termination — and, if a `post_logout_redirect_uri` is
supplied, validates it against the client's `postLogoutRedirectUris` and returns where to redirect.

## Security notes

- Redirect URIs are matched exactly against the client's allowlist and must be HTTPS (localhost
  excepted); custom schemes (native apps) are allowed.
- PKCE is mandatory for public clients and uses `S256` only (`plain` is rejected).
- Authorization codes are single-use and short-lived (cache, 10 min default).
- Client secrets are stored only as salted hashes and compared in constant time.
- A client can only revoke/introspect tokens that were issued to it.
- Access tokens carry only their granted OAuth scopes (e.g. `openid profile`), not your app's root
  scope, so they cannot be used as general app credentials.

## Limitations

- Only the Authorization Code flow (`response_type=code`) is implemented (implicit/hybrid are
  deprecated and intentionally omitted).
- ID-token `aud` is a single client id.
- Dynamic client registration (RFC 7591) is not included; register clients via
  `OauthClientEndpoints`.
- The login and consent UI are your application's responsibility (see "API-only design").
