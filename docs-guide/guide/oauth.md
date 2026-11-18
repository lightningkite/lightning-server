# OAuth

"Sign in with Google" is another proof method.  `OauthProofEndpoints` sends the browser to the provider, receives the
callback, reads the user's verified email from the provider, and redirects to your app with a signed email `Proof`.
The app posts that proof to `/auth/login` like any other (see [Proof-based login](proof-session.md)).  Add the
`sessions-oauth` module.

## Minimal example

<!-- sample: com/lightningkite/lightningserver/guide/samples/OauthSamples.kt#oauth-imports -->
```kotlin
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.sessions.proofs.*
import com.lightningkite.lightningserver.sessions.proofs.oauth.*
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.cache.*
import io.ktor.http.encodeURLQueryComponent
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/OauthSamples.kt#oauth-server -->
```kotlin
object SocialLoginServer : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())
    val googleOauth = setting("googleOauth", OauthProviderCredentials(id = "", secret = ""))

    val proofGoogle = path.path("proof").path("google") module OauthProofEndpoints(
        provider = OauthProviderInfo.google,
        cache = cache,
        credentials = googleOauth,
        continueUiAuthUrl = { proof ->
            val json = serverRuntime.externalSerialization.json.encodeToString(Proof.serializer(), proof)
            "https://app.example.com/login/continue?proof=" + json.encodeURLQueryComponent()
        },
    )
}
```

In a real server this sits next to the `AuthEndpoints` and other proof methods from
[Proof-based login](proof-session.md#minimal-example).  The proof is for property `email` with strength 10, so
`PrincipalType.fetchByProperty` must handle `"email"`; whether it creates accounts on first sign-in is your choice.
To prove a different property, pass `property` and a `makeProof` that proves it.

Fill in the credentials in settings:

```json
{
    "googleOauth": { "id": "1234-abc.apps.googleusercontent.com", "secret": "GOCSPX-..." }
}
```

## The flow

```
1. App      GET  /proof/google/start      "alice@example.com" (a login hint, or "")
            -> "https://accounts.google.com/o/oauth2/v2/auth?client_id=…&state=…&code_challenge=…"
2. Browser  opens that URL; the user signs in at Google
3. Google   POST /proof/google/callback   (GitHub: GET with query parameters)
4. Server   exchanges the code, reads the profile, signs a Proof
            -> 302 to continueUiAuthUrl(proof)
5. App      reads the proof from its URL and POSTs it to /auth/login
```

`GET /proof/google/login` returns the same URL without a hint, and `GET /proof/google/open` redirects straight to the
provider, for a plain "Sign in with Google" link.

Register `<public URL>/<mount path>/callback` as the redirect URI in the provider's console, for example
`https://api.example.com/proof/google/callback`.  The public URL comes from the `general` settings' `publicUrl`.

`continueUiAuthUrl` decides where the proof goes.  It runs in a `ServerRuntime`, and only the server-signed proof feeds
it, so the redirect can't be steered by the caller.  Point it at a page in your app that posts the proof to
`/auth/login`; the [demo server](https://github.com/lightningkite/lightning-server/blob/master/demo/src/main/kotlin/com/lightningkite/lightningserver/demo/Server.kt)
does that server-side in an `autosignin` endpoint.

## Providers

| Provider | `OauthProviderInfo.` | Credentials | Email used |
|---|---|---|---|
| Google | `google` | `OauthProviderCredentials(id, secret)` | Only if Google reports it verified |
| GitHub | `github` | `OauthProviderCredentials(id, secret)` | The primary address, only if verified |
| Microsoft | `microsoft` | `OauthProviderCredentials(id, secret)` | As returned by Microsoft Graph, **unverified** (see the warning below) |
| Apple | `apple` | `OauthProviderCredentialsApple(serviceId, teamId, keyId, keyString)` | From the verified ID token, only if `email_verified` |

A profile with no usable email fails the callback with 400.  Each provider is its own proof method (its `via` is the
provider's name, e.g. `google`), but all of them, and the email PIN, verify property `email`, so they don't add up:
the same person signing in with Google and GitHub reaches the same account at strength 10.

Console setup, in short:

- **Google**: create an OAuth client ID (type *Web application*) at console.cloud.google.com, after configuring the
  consent screen with the `userinfo.email` scope.  Add the callback as an authorized redirect URI.
- **GitHub**: create an OAuth App under *Settings → Developer settings*; the callback is the authorization callback URL.
- **Microsoft**: register an app in the Azure portal, add the callback as a redirect URI, add the `email` and
  `User.Read` permissions, and create a client secret.  Use its *value* as `secret`.
- **Apple**: create an App ID and a Services ID with *Sign in with Apple*, add the callback to the Services ID, and
  create a key.  `keyString` is the `.p8` file's contents; the framework signs the short-lived client-secret JWT from it.

!!! warning
    Microsoft Graph doesn't say whether the email it returns is verified, and a Microsoft account or Entra tenant can
    carry an address its owner doesn't control.  Until that is resolved, don't let a Microsoft sign-in reach an
    existing account by email alone: register the app as multi-tenant with unverified email claims removed, or
    identify Microsoft users by another property.

For Apple, pass `credentials = Runtime { appleCredentials().toOauthProviderCredentials() }`, where `appleCredentials`
is an `OauthProviderCredentialsApple` setting; the conversion signs a fresh client-secret JWT.

### Custom providers

Describe any OAuth 2.0 provider with `OauthProviderInfo`:

```kotlin
val acme = OauthProviderInfo(
    niceName = "Acme",
    loginUrl = "https://id.acme.example/oauth/authorize",
    tokenUrl = "https://id.acme.example/oauth/token",
    scopeForProfile = "openid email",
    getProfile = { response, credentials -> fetchAcmeProfile(response.access_token) },
)
```

`getProfile` receives the token response (`access_token`, `id_token`, ...) and returns an
`ExternalProfile(id, email, username, name, image)`; `fetchAcmeProfile` stands for your call to the provider's
user-info API.  Set `mode = OauthResponseMode.query` if the provider redirects back with a GET, and `supportsPkce = false` only if it
rejects PKCE parameters.  Only return an email the provider has verified: whoever controls it gets the account.

## Security notes

- The `state` sent to the provider is a single-use random nonce held in `cache`, and PKCE protects the code exchange.
  The flow lives in the cache for 10 minutes, so with more than one server instance `cache` must be shared (not `ram`).
- The nonce isn't bound to the browser that started the flow.  That stops forged callbacks, but not "login CSRF",
  where an attacker completes their own sign-in and tricks a victim into opening the resulting callback URL.
- The proof travels in a URL, so it can end up in browser history.  It is single-use and expires after
  `proofExpiration` (default 1 hour); shorten that if it matters.

## See also

- [Proof-based login](proof-session.md): `AuthEndpoints`, strength, and sessions
- [Authentication](auth.md)
- [Settings](settings.md): where credentials live
