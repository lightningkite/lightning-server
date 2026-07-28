> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# OAuth / Social Login

OAuth social login lets users authenticate with an existing account at Google, Apple, GitHub, Microsoft, or any OAuth 2.0 provider.  On the Lightning Server side, OAuth is just another **proof method** — one that delegates identity verification to a trusted third party instead of asking the user for a PIN or password directly.  The rest of the auth pipeline (proof accumulation, session creation, bearer tokens) is identical to any other proof method.

If you haven't read [Proof & Session Authentication](../guide/proof-session.md) yet, skim it first — this page builds directly on that model.

---

## How OAuth fits the proof model

Every proof carries three things: a **property** (the identity attribute it verifies), a **value** (the attribute's value), and a **strength** (a numeric confidence score).

`OauthProofEndpoints` always emits proofs with:

| field | value |
|---|---|
| `property` | `"email"` |
| `value` | the email address returned by the provider |
| `strength` | **10** (the highest built-in strength) |

A strength of 10 is the highest in the framework because the identity is verified by a trusted external party — not just by something the user knows.  If your `requiredProofStrengthFor` is ≤ 10 (it nearly always is), a single OAuth proof is enough to open a session on its own.

---

## Gradle dependency

Add the `sessions-oauth` module to your server module's dependencies.  You do not need `sessions-oauth-shared` for a server-only setup; that module exists for client-side models shared with a KMP client app.

```kotlin
// build.gradle.kts (server module)
dependencies {
    api(project(":sessions-oauth"))
}
```

---

## Imports

All server-side examples in this page use the following imports:

```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.sessions.*
import com.lightningkite.lightningserver.sessions.proofs.*
import com.lightningkite.lightningserver.sessions.proofs.oauth.*
import kotlinx.serialization.*
import kotlin.time.*
import kotlin.uuid.*
```

---

## Credentials: `OauthProviderCredentials`

All built-in providers (except Apple) use the same credentials type:

```kotlin
@Serializable
data class OauthProviderCredentials(
    val id: String,     // OAuth client ID (from the provider console)
    val secret: String, // OAuth client secret
)
```

Declare it as a setting in your `ServerBuilder` so it lands in `settings.json` and can be injected at runtime:

```kotlin
object Server : ServerBuilder() {
    // ...
    val googleOAuth = setting("googleOAuth", OauthProviderCredentials("", ""))
}
```

When the server generates its default `settings.json` on first run, `googleOAuth` will appear with empty strings.  Fill in the values from your provider console before the next run.

### Apple: `OauthProviderCredentialsApple`

Apple's token-exchange flow requires a short-lived JWT signed with a P-256 private key rather than a static client secret.  `OauthProviderCredentialsApple` holds the raw key material and generates that JWT on demand:

```kotlin
@Serializable
data class OauthProviderCredentialsApple(
    val serviceId: String,   // Service ID from Apple developer console
    val teamId: String,      // Your Apple team identifier
    val keyId: String,       // The key's ID from Apple developer console
    val keyString: String,   // Contents of the .p8 file (without the PEM header/footer lines)
)
```

Declare it as a separate setting:

```kotlin
val appleOAuth = setting("appleOAuth", OauthProviderCredentialsApple("", "", "", ""))
```

The framework's Apple provider (`OauthProviderInfo.apple`) uses `SettingInfo.apple`, which automatically calls `toOauthProviderCredentials()` to produce a fresh JWT each time credentials are needed.  You never call this yourself.

---

## Built-in providers

`OauthProviderInfo` ships four pre-configured providers as companion object properties:

| Property | Provider | Credentials type | Scopes requested |
|---|---|---|---|
| `OauthProviderInfo.google` | Google | `OauthProviderCredentials` | `https://www.googleapis.com/auth/userinfo.email` |
| `OauthProviderInfo.apple` | Apple Sign In | `OauthProviderCredentialsApple` | `email` |
| `OauthProviderInfo.microsoft` | Microsoft / Azure AD | `OauthProviderCredentials` | `openid email profile` |
| `OauthProviderInfo.github` | GitHub | `OauthProviderCredentials` | `user:email read:user` |

Pass the appropriate constant as the `provider` argument to `OauthProofEndpoints`.

---

## Mounting `OauthProofEndpoints`

Mount `OauthProofEndpoints` using the `module` infix, exactly like any other proof method:

```kotlin
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache    = setting("cache",    Cache.Settings())
    val email    = setting("email",    Email.Settings())
    val googleOAuth = setting("googleOAuth", OauthProviderCredentials("", ""))

    val pins       = PinHandler(cache, "pins")
    val proofEmail = path.path("proof").path("email") module EmailProofEndpoints(pins, email) { to, pin ->
        Email(subject = "Your PIN", to = listOf(EmailAddressWithName(to)), plainText = "PIN: $pin")
    }

    // Mount the Google OAuth proof method at /proof/google
    val proofGoogle = path.path("proof").path("google") module OauthProofEndpoints(
        provider = OauthProviderInfo.google,
        credentials = googleOAuth,
        continueUiAuthUrl = { proof ->
            // The browser will be sent here after Google authenticates the user.
            // Encode the signed Proof into the URL so the client can pick it up.
            // Replace "https://myapp.com/auth/continue" with your real front-end route.
            "https://myapp.com/auth/continue?proof=" +
                serverRuntime.externalSerialization.json
                    .encodeToString(Proof.serializer(), proof)
                    .encodeURLQueryComponent()
        }
    )

    val auth = path.path("auth") module object : AuthEndpoints<User, Uuid>(
        principal = UserAuth,
        database  = database,
        cache     = cache,
    ) {
        context(server: ServerRuntime)
        override suspend fun requiredProofStrengthFor(subject: User): Int = 5

        context(server: ServerRuntime)
        override suspend fun sessionExpiration(subject: User): Instant? = null

        context(server: ServerRuntime)
        override suspend fun sessionStaleAfter(subject: User): Duration? = null
    }

    init { registerBasicMediaTypeCoders() }
}
```

### Constructor parameters

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `provider` | `OauthProviderInfo` | (required) | Which provider to use |
| `credentials` | `Runtime<OauthProviderCredentials>` | (required) | Setting reference for client credentials |
| `continueUiAuthUrl` | `context(ServerRuntime) (Proof) -> String` | (required) | Where to redirect the browser after a successful OAuth round-trip |
| `proofSigner` | `RuntimeDeferred<Signer>` | `secretBasis.signer("proof")` | The signer used to HMAC-sign the generated `Proof`; share this across all proof methods so proofs are cross-validatable |
| `proofExpiration` | `Duration` | `1.hours` | How long the returned `Proof` is valid before it must be exchanged for a session |

---

## The redirect flow, step by step

```
1. Client  ──► GET /proof/google/start?value=hint@example.com
               (or GET /proof/google/open for no login hint)

2. Server  ──► 302 → https://accounts.google.com/o/oauth2/v2/auth?...
               (redirect to Google with client_id, scope, state UUID, redirect_uri pointing at /proof/google/callback)

3. Browser ──► Google login UI ──► user grants access

4. Google  ──► POST /proof/google/callback  (form-post; or GET for GitHub which uses query mode)
               body: { code, state }

5. Server  ──► exchanges code for access token ──► fetches user profile from Google
               ──► extracts verified email
               ──► builds a signed Proof { property="email", value=email, strength=10 }
               ──► 302 → continueUiAuthUrl(proof)

6. Browser ──► lands on your front-end URL with the proof in the query string

7. Client  ──► POST /auth/login   body: <Proof>
               ──► session issued ──► bearer token returned
```

Steps 1–5 happen entirely in the browser (the user never sees step 4 explicitly).  Step 7 is a normal API call from the client app.

### The `start` endpoint

`OauthProofEndpoints` implements `ExternalProofMethod`, which means it exposes a typed `start` endpoint:

```
GET /proof/google/start
Input body: String   // optional login-hint email; pass "" to omit
Output: String       // the full Google authorization URL; open this in the browser
```

There is also a simpler `open` endpoint (`GET /proof/google/open`) that redirects the browser directly to the provider without going through the API.  Use `start` when your client needs to control when and how the browser navigates; use `open` for a plain link or redirect.

### The callback URL to register with the provider

The callback URL that you register in your provider's developer console is:

```
<your server's public URL>/proof/<provider-path>/callback
```

For example, if your server runs at `https://api.myapp.com` and you mounted the Google provider at `path.path("proof").path("google")`, register:

```
https://api.myapp.com/proof/google/callback
```

> Note: most providers (Google, Apple, Microsoft) post the authorization code as `application/x-www-form-urlencoded` (form-post mode).  GitHub uses a query-string redirect instead.  The framework handles both automatically via `OauthProviderInfo.mode`; you do not need to configure this.

---

## The `continueUiAuthUrl` callback

After the provider callback succeeds, the server calls your `continueUiAuthUrl` lambda with the signed `Proof`, then issues an HTTP redirect to whatever URL you return.  This lambda runs inside a `ServerRuntime` context, so you can access services or settings if needed.

The lambda must return a URL that your front-end can handle.  The convention is to encode the proof as a JSON query parameter (URL-encoded), but the exact format is up to you — the client just needs to recover the `Proof` object and POST it to `/auth/login`.

A typical implementation encodes the proof with the standard JSON serializer:

```kotlin
// Illustrative — adjust the base URL and query param name to match your front end.
continueUiAuthUrl = { proof ->
    "https://myapp.com/auth/continue?proof=" +
        serverRuntime.externalSerialization.json
            .encodeToString(Proof.serializer(), proof)
            .encodeURLQueryComponent()
}
```

The demo server additionally passes the backend's public URL as a `backend` query parameter so that a single front-end build can target multiple environments:

```kotlin
// From the demo server — illustrative.
continueUiAuthUrl = { proof ->
    autosignIn.location.path.resolved().fullUrl() +
        "?proof=" + serverRuntime.externalSerialization.json
            .encodeToString(Proof.serializer(), proof)
            .encodeURLQueryComponent() +
        "&backend=" + generalSettings().publicUrl.encodeURLQueryComponent()
}
```

---

## Auto-registration: `fetchByProperty`

When `AuthEndpoints` processes a proof with `property = "email"`, it calls:

```kotlin
principal.fetchByProperty("email", emailValue)
```

to find which user owns that email address.  The default `PrincipalType.fetchByProperty` implementation only handles the `_id` property and returns `null` for everything else.  **You must override it** to handle `"email"` or users will never be found and every OAuth login will fail with "No user was found".

Override it in your `PrincipalType` object.  You have two choices:

**Strict (require pre-existing account):**

```kotlin
object UserAuth : PrincipalType<User, Uuid> {
    // ...

    context(server: ServerRuntime)
    override suspend fun fetchByProperty(property: String, value: String): User? {
        return when (property) {
            "email" -> Server.userTable()
                .findOne(condition { it.email eq value })
            else -> super.fetchByProperty(property, value)
        }
    }
}
```

If no user with that email exists, `fetchByProperty` returns `null`, and the session exchange endpoint returns an error.  The client must create an account first.

**Auto-registration (create on first login):**

```kotlin
context(server: ServerRuntime)
override suspend fun fetchByProperty(property: String, value: String): User? {
    return when (property) {
        "email" -> Server.userTable().findOne(condition { it.email eq value })
            ?: Server.userTable().insertOne(User(email = value))
        else -> super.fetchByProperty(property, value)
    }
}
```

The demo server uses auto-registration.  Inserting a new `User` on first login means the client doesn't need a separate "sign up" flow for OAuth users.

---

## From proof to session

Once the client recovers the `Proof` from the redirect URL and posts it to `/auth/login`, the standard [proof accumulation flow](../guide/proof-session.md) runs:

1. The server validates the proof's HMAC signature (produced by `proofSigner`).
2. It calls `claimOnce` on the proof's fingerprint — replay attacks are blocked.
3. It calls `fetchByProperty("email", proof.value)` to locate (or create) the subject.
4. It sums proof strengths.  A single OAuth proof contributes strength 10.
5. It compares the total against `requiredProofStrengthFor(subject)`.
6. If the threshold is met, it creates a `Session` record and returns a signed bearer-token pair.

The client then uses the access token in the `Authorization: Bearer …` header on all subsequent requests, exactly as documented in [Proof & Session Authentication](../guide/proof-session.md).

---

## Multiple OAuth providers

Mount each provider at its own path segment.  Each `OauthProofEndpoints` instance is independent:

```kotlin
// Illustrative — requires separate credential settings for each provider.
val googleOAuth = setting("googleOAuth", OauthProviderCredentials("", ""))
val githubOAuth = setting("githubOAuth", OauthProviderCredentials("", ""))

val proofGoogle = path.path("proof").path("google") module OauthProofEndpoints(
    provider = OauthProviderInfo.google,
    credentials = googleOAuth,
    continueUiAuthUrl = { proof -> "https://myapp.com/auth/continue?proof=..." }
)
val proofGithub = path.path("proof").path("github") module OauthProofEndpoints(
    provider = OauthProviderInfo.github,
    credentials = githubOAuth,
    continueUiAuthUrl = { proof -> "https://myapp.com/auth/continue?proof=..." }
)
```

Both providers emit `property = "email"` proofs.  If the same user logs in with their Google account and their GitHub account (both sharing the same verified email address), `fetchByProperty` returns the same `User` record — no duplicate accounts.

---

## Apple Sign In

Apple requires a dedicated credentials type and a Service Identifier (not just a standard app bundle ID):

```kotlin
val appleOAuth = setting("appleOAuth", OauthProviderCredentialsApple("", "", "", ""))

val proofApple = path.path("proof").path("apple") module OauthProofEndpoints(
    provider = OauthProviderInfo.apple,
    credentials = appleOAuth,  // the framework coerces this automatically via SettingInfo.apple
    continueUiAuthUrl = { proof -> "https://myapp.com/auth/continue?proof=..." }
)
```

The `OauthProviderInfo.apple` object uses `SettingInfo.apple` which calls `OauthProviderCredentialsApple.toOauthProviderCredentials()` each time credentials are needed; this generates a fresh JWT signed with your P-256 private key (required because Apple's client-secret JWTs expire in ≤ 6 months).

**Apple developer console setup** (illustrative — verify steps against Apple's current documentation):

1. Sign in to [developer.apple.com](https://developer.apple.com).
2. In **Certificates, Identifiers & Profiles**, create or edit an **App Identifier** and enable the **Sign in with Apple** capability.
3. Create a **Service Identifier** (this becomes `serviceId`).  Under the service ID, configure **Sign in with Apple** and add your callback URL: `https://api.myapp.com/proof/apple/callback`.
4. Create a **Key** with Sign in with Apple enabled; download the `.p8` file.
5. Populate your settings:
   - `serviceId` — the Service ID reverse-domain string
   - `teamId` — your 10-character team identifier (shown in the top-right of the console)
   - `keyId` — the Key ID shown in the Keys list
   - `keyString` — the contents of the `.p8` file, **without** the `-----BEGIN PRIVATE KEY-----` / `-----END PRIVATE KEY-----` lines

---

## Provider console setup quick reference

> The URLs and exact steps below are illustrative; verify them against each provider's current documentation.

### Google

- Console: [console.cloud.google.com](https://console.cloud.google.com)
- Create a project → **APIs & Services** → **OAuth consent screen** → fill in app info.
- Enable scopes: `userinfo.email` and `userinfo.profile`.
- **Credentials** → **Create credentials** → **OAuth 2.0 Client ID** (type: Web application).
- Add `https://api.myapp.com/proof/google/callback` to **Authorized redirect URIs**.
- Copy **Client ID** → `OauthProviderCredentials.id`; **Client Secret** → `OauthProviderCredentials.secret`.

### GitHub

- [github.com/settings/developers](https://github.com/settings/developers) → **OAuth Apps** → **New OAuth App**.
- **Authorization callback URL**: `https://api.myapp.com/proof/github/callback`.
- Copy **Client ID** and generate a **Client Secret**.

### Microsoft

- [portal.azure.com](https://portal.azure.com) → **Azure Active Directory** → **App registrations** → **New registration**.
- Under **Authentication**, add a redirect URI: `https://api.myapp.com/proof/microsoft/callback`.
- Under **API Permissions**, add `email` and `User.Read`.
- Under **Certificates & secrets**, create a new **client secret**; copy the **Value** (not the Secret ID).
- **Application (client) ID** → `OauthProviderCredentials.id`; secret value → `OauthProviderCredentials.secret`.

---

## Security notes

- **Proof signatures are HMAC-verified** — the signed `Proof` the client receives cannot be tampered with (the value field is bound to the signature).
- **Proofs are single-use** — the framework's `claimOnce` mechanism prevents a captured proof URL from being replayed.
- **Email is the identity property** — both Google and GitHub verify the email address before including it in the profile.  Apple verifies via JWT claims (`email_verified`).  The framework rejects unverified emails and throws `BadRequestException`.
- **The `continueUiAuthUrl` lambda controls where the proof goes** — make sure it points at a URL you control and not an open redirector.
- **Apple JWT secrets expire** — `OauthProviderCredentialsApple.generateJwt()` produces a 5-day JWT (issued 1 day in the past to account for clock skew).  The framework regenerates the JWT on every call, so you never need to rotate the setting manually.

---

## What's not covered here

- **Using Lightning Server as an OAuth _provider_** (issuing tokens to third-party clients) — see `OauthClientEndpoints` in the `sessions-oauth` module.  That is a separate concept from this page.
- **Custom OAuth providers** — `OauthProviderInfo` can be constructed with arbitrary `loginUrl`, `tokenUrl`, `scopeForProfile`, and `getProfile` parameters.  This is straightforward but not verified in a compiled sample here.
- **Offline access / refresh tokens from the provider** — `OauthCallbackEndpoint.accessToken(refreshToken)` exists but usage is not covered in this draft.

---

## Further reading

- [Proof & Session Authentication](../guide/proof-session.md) — the full proof model, session exchange, and bearer token lifecycle
- [Authentication & Sessions](../guide/auth.md) — `PrincipalType`, `authOptions`, reading `auth` inside handlers
- Demo server: `demo/src/main/kotlin/com/lightningkite/lightningserver/demo/Server.kt` — working GitHub OAuth wiring
- `sessions-oauth/src/main/kotlin/.../sessions/proofs/OauthProofEndpoints.kt` — `OauthProofEndpoints` source
- `sessions-oauth/src/main/kotlin/.../sessions/proofs/oauth/OauthProviderInfo.kt` — built-in provider definitions
