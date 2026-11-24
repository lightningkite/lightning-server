# Proof-based login

Users log in by proving facts about themselves: they own an email address or phone number, they know a password,
they hold an authenticator app, a passkey, or a device they used before.  Each proof method issues a signed,
short-lived `Proof`.  `AuthEndpoints` adds up the proofs' strength, and once it is enough, creates a session and returns
a long-lived **refresh token**.  The client trades that for short-lived **access tokens**, which become the `auth` your
handlers read (see [Authentication](auth.md)).

| Module | Provides |
|---|---|
| `sessions` | `AuthEndpoints`, `SessionManager`, `PinHandler`, password, known-device, and backup-code proofs, token formats |
| `sessions-email` | `EmailProofEndpoints` |
| `sessions-sms` | `SmsProofEndpoints` |
| `sessions-totp` | `TimeBasedOTPProofEndpoints` |
| `sessions-webauthn` | `WebAuthNProofEndpoints` (passkeys) |
| `sessions-oauth` | `OauthProofEndpoints`; see [OAuth](oauth.md) |

## Minimal example

<!-- sample: com/lightningkite/lightningserver/guide/samples/ProofSessionSamples.kt#proof-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.sessions.*
import com.lightningkite.lightningserver.sessions.proofs.*
import com.lightningkite.lightningserver.sessions.proofs.PinBasedProofEndpoints.ScreeningResult
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.cache.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import com.lightningkite.services.email.*
import com.lightningkite.services.sms.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.Duration.Companion.days
import kotlin.uuid.*
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/ProofSessionSamples.kt#account-model -->
```kotlin
@Queryable
data class Account(
    override val _id: Uuid = Uuid.random(),
    val email: EmailAddress,
    val phone: PhoneNumber? = null,
    val isAdmin: Boolean = false,
) : HasId<Uuid>
```

A proof says "property `email` has value `alice@example.com`".  `fetchByProperty` turns that into an account.  This one
creates an account on the first email login and requires an existing account for phone logins:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ProofSessionSamples.kt#account-auth -->
```kotlin
object AccountAuth : PrincipalType<Account, Uuid> {
    override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
    override val subjectSerializer: KSerializer<Account> = Account.serializer()

    context(server: ServerRuntime)
    override suspend fun fetch(id: Uuid): Account =
        LoginServer.accounts().get(id) ?: throw NotFoundException("No such account")

    context(server: ServerRuntime)
    override suspend fun fetchByProperty(property: String, value: String): Account? = when (property) {
        "email" -> {
            val email = value.toEmailAddress()
            LoginServer.accounts().findOne(Condition { it.email eq email })
                ?: LoginServer.accounts().insertOne(Account(email = email))
        }
        "phone" -> LoginServer.accounts().findOne(Condition { it.phone eq value.toPhoneNumber() })
        else -> super.fetchByProperty(property, value)
    }
}
```

The server mounts each proof method at a path you choose, and `AuthEndpoints` at another:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ProofSessionSamples.kt#login-server -->
```kotlin
object LoginServer : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())
    val email = setting("email", EmailService.Settings())
    val sms = setting("sms", SMS.Settings())

    val accounts = database.registerTable<Account>("Account")

    val pins = PinHandler(cache, "pins")

    val proofEmail = path.path("proof").path("email") module EmailProofEndpoints(
        pin = pins,
        email = email,
        emailTemplate = { to, pin ->
            Email(subject = "Your login code", to = listOf(EmailAddressWithName(to)), plainText = "Your code is $pin.")
        },
    )
    val proofSms = path.path("proof").path("sms") module SmsProofEndpoints(pins, sms)
    val proofPassword = path.path("proof").path("password") module PasswordProofEndpoints(
        database, cache,
        evaluatePassword = { if (it.password.length < 12) throw BadRequestException("Use at least 12 characters.") },
    )
    val proofTotp = path.path("proof").path("totp") module TimeBasedOTPProofEndpoints(database, cache)
    val proofDevice = path.path("proof").path("device") module KnownDeviceProofEndpoints(database, cache)
    val proofBackupCode = path.path("proof").path("backup-code") module BackupCodeEndpoints(database, cache)

    val auth = path.path("auth") module object : AuthEndpoints<Account, Uuid>(
        principal = AccountAuth,
        database = database,
        cache = cache,
    ) {
        context(server: ServerRuntime)
        override suspend fun requiredProofStrengthFor(subject: Account): Int = if (subject.isAdmin) 20 else 10

        context(server: ServerRuntime)
        override suspend fun sessionExpiration(subject: Account): Instant? = now() + 90.days

        context(server: ServerRuntime)
        override suspend fun sessionStaleAfter(subject: Account): Duration? = 30.days
    }

    init {
        registerBasicMediaTypeCoders()
    }
}
```

- `requiredProofStrengthFor`: how much proof this account needs.  Here admins need two factors.
- `sessionExpiration`: the latest a new session may expire (null for no limit).
- `sessionStaleAfter`: how long a session may go unused before it expires (null for never).  Each use resets it.
- Every proof method registers itself; `AuthEndpoints` finds them all, so mounting one is all it takes to offer it.

## The login flow

Over HTTP, an email login is five calls:

```
POST /proof/email/start      "alice@example.com"
  -> "c0a1…"                                        a lookup key; the PIN goes to the inbox
POST /proof/email/prove      {"key": "c0a1…", "password": "KXWTRE"}
  -> {"via": "email", "property": "email", "value": "alice@example.com", "strength": 10, …, "signature": "…"}
POST /auth/login             [<proof>, …]
  -> {"id": "…", "options": […], "strengthRequired": 10, "refreshToken": "refresh/Account/…"}
POST /auth/token/simple      "refresh/Account/…"
  -> "Account/…"                                    the access token, valid for 5 minutes
GET  /auth/self              Authorization: Bearer Account/…
```

If the proofs aren't strong enough, `login` returns no `refreshToken`.  `options` then lists the methods this account
has set up and hasn't used yet, and `strengthRequired` the total to reach: collect another proof and call `login`
again with all of them.  `POST /auth/proofs-check` returns the same information without creating a session and
without using up the proofs, for checking each step of a multi-step form.

Access tokens expire after 5 minutes; get a new one from `token/simple` with the refresh token.

### Testing a login

The `test` email and SMS backends keep sent messages in memory, so a test can run the whole flow:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ProofSessionSamples.kt#email-login-test -->
```kotlin
fun emailLoginTest() = LoginServer.testBlocking(settings = {
    database set Database.Settings("ram")
    cache set Cache.Settings("ram")
    email set EmailService.Settings("test")
}) {
    // 1. Ask for a PIN.  The response is a lookup key, not the PIN.
    val key = proofEmail.start.test(null, "alice@example.com")

    // 2. The user reads the PIN from their inbox.
    val sent = (email() as TestEmailService).lastEmail()!!
    val pin = Regex("[A-Z]{6}").find(sent.plainText)!!.value

    // 3. Trade the key and PIN for a signed proof.
    val proof = proofEmail.prove.test(null, FinishProof(key = key, password = pin))

    // 4. Log in with the proof to get a refresh token.
    val refreshToken = auth.login.test(null, listOf(proof)).refreshToken!!

    // 5. Trade the refresh token for a short-lived access token, and use it.
    val accessToken = auth.tokenSimple.test(null, refreshToken)
    val response = auth.self.test(headers = HttpHeaders("Authorization" to "Bearer $accessToken"))
    check(response.status == HttpStatus.OK)
}
```

For endpoints that merely need a signed-in user, `testAuth` is simpler; see [Authentication](auth.md#testing).
SMS works the same way with `SMS.Settings("test")` and `(sms() as TestSMS).messageHistory`.

## Proof strength

| Method | `via` | Counts under | Strength |
|---|---|---|---|
| `EmailProofEndpoints` | `email` | `email` | 10 |
| `SmsProofEndpoints` | `sms` | `phone` | 5 |
| `PasswordProofEndpoints` | `password` | `password` | 10 |
| `TimeBasedOTPProofEndpoints` | `totp` | `totp` | 10 |
| `WebAuthNProofEndpoints` | `WebAuthN` | `WebAuthN` | 10, or 20 if the authenticator verified the user |
| `KnownDeviceProofEndpoints` | `known-device` | `known-device` | 3 |
| `BackupCodeEndpoints` | `backupcode` | `backupcode` | 10 per proof, but 0 toward what the account must reach |
| `OauthProofEndpoints` | the provider, e.g. `google` | `email` | 10 |

How `login` decides:

1. Each proof's signature and expiry are checked, and `fetchByProperty` must resolve every proof to the same account.
2. Proofs are grouped by the property their method verifies (or by the method, when it verifies none, like a password).
   Only the strongest proof in each group counts, and the groups are summed.  Two email-ownership proofs, or an email
   PIN plus Google sign-in, count once.
3. The strength required is `requiredProofStrengthFor(account)`, capped at the most the account's *set-up* methods
   could reach.  An email method is set up when the account has an email; a password when one has been established.

That cap means an admin who has only an email still logs in with strength 10.  To enforce a second factor, make sure it
gets set up: for example, override `permitScopes` to grant only what's needed to establish a second method until one
exists.

## Proof methods

All proof methods take a `proofSigner` (default `secretBasis.signer("proof")`, which every method must share) and a
`proofExpiration` (default 1 hour: how long a proof may wait before it's used in a login).  Sending a PIN, and proving
a password, TOTP code, known device, or backup code, are rate limited per target: 5 attempts per 5 minutes, then
blocks that double up to 3 hours.  A PIN itself allows `PinHandler.maxAttempts` guesses.

Managing credentials (setting a password, enrolling TOTP, registering a passkey, creating backup codes) requires an
authentication with the `auth:proofs:<via>` scope, or root, that proved identity **within the last 10 minutes**.  A
user who logged in long ago must log in again first.

### Email PIN

`EmailProofEndpoints(pin, email, emailTemplate, proofSigner, proofExpiration, screenEmail)` mounts `start` and `prove`.
Addresses are trimmed and lowercased, and an invalid address is a 400.  `emailTemplate` receives the `EmailAddress` and
the PIN.

`screenEmail` (and `screenPhone` for SMS) runs before a PIN is created and decides what happens:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ProofSessionSamples.kt#screened-email -->
```kotlin
object ScreenedEmailServer : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())
    val email = setting("email", EmailService.Settings())

    val proofEmail = path.path("proof").path("email") module EmailProofEndpoints(
        pin = PinHandler(cache, "pins"),
        email = email,
        emailTemplate = { to, pin ->
            Email(subject = "Your login code", to = listOf(EmailAddressWithName(to)), plainText = "Your code is $pin.")
        },
        screenEmail = { address ->
            when {
                address.raw.endsWith("@mailinator.com") ->
                    ScreeningResult.Reject("Please use a permanent email address.", detail = "disposable-email")
                address.raw.endsWith("@example.com") -> ScreeningResult.Suppress
                else -> ScreeningResult.Allow
            }
        },
    )
}
```

| Result | Client sees | Message sent |
|---|---|---|
| `ScreeningResult.Allow` (default) | 200 with a key | Yes |
| `ScreeningResult.Suppress` | 200 with a key that behaves like a real one | No |
| `ScreeningResult.Reject(message, detail)` | 400 with `message` and `detail` (default `target-rejected`) | No |

Prefer `Suppress` when a rejection would reveal whether an account exists.  Rejections count toward the rate limit.

To send a magic link instead of a PIN, call `proofEmail.send(destination, request) { proof -> Email(...) }` from your
own endpoint: it issues a proof for that address and lets you embed it in the email.  Screening doesn't apply.

### SMS PIN

`SmsProofEndpoints(pin, sms, smsTemplate, proofSigner, proofExpiration, screenPhone)`.  The default message is
`Your <projectName> code is <PIN>. Don't share this with anyone.`; `smsTemplate` receives the PIN.  Numbers are
normalized to E.164 before anything else: extensions after `x` are dropped, non-digits removed, and 10-digit numbers
get the US country code (`(555) 123-4567` becomes `+15551234567`).  The proof's value is that normalized form, so store
phone numbers as `PhoneNumber`.

SMS proofs are strength 5: by itself a phone can't meet a requirement of 10.

### PinHandler

Email and SMS share a `PinHandler`, which keeps PINs in the cache:

| Parameter | Default | |
|---|---|---|
| `cache` | | Use a shared cache (not `ram`) when running more than one instance. |
| `keyPrefix` | | Namespaces this handler's cache entries. |
| `availableCharacters` | `A`-`Z` without `I` and `O` | Case-insensitive unless the set mixes cases. |
| `length` | 6 | |
| `expiration` | 15 minutes | |
| `maxAttempts` | 5 | Wrong guesses before the PIN is discarded. |

### Password

`PasswordProofEndpoints(database, cache, proofSigner, proofExpiration, signals, evaluatePassword)`.

- `POST establish` with `EstablishPassword(password, hint)` sets the caller's password; older ones are disabled.
  `evaluatePassword` sees the plain password first and can throw to reject it.  Server code can call
  `establish(principal, id, EstablishPassword(...))` directly.
- `POST prove` with `IdentificationAndPassword(type = "Account", property = "email", value = "alice@example.com",
  password = "…")`.  `type` is the principal's name.  Failed attempts take at least a second.
- Passwords are hashed with PBKDF2 (see [Encryption](encryption.md#password-hashing)) in a `PasswordSecret` table,
  exposed at `secrets` for the owner and `AuthRequirement.IsAdmin`.

### TOTP (authenticator apps)

`TimeBasedOTPProofEndpoints(database, cache, proofSigner, proofExpiration, config, signals, accountName)`.

- `POST establish` with `EstablishTotp(label)` returns an `otpauth://` URL to show as a QR code.
- `POST confirm` with the first code from the app activates it.  An existing authenticator keeps working until the new
  one is confirmed, so abandoning setup never locks anyone out.
- `POST prove` takes `IdentificationAndPassword` with the current code as `password`.  A code can't be used twice.
- `accountName` picks the account label the app shows, e.g. `{ (it as Account).email.raw }`.

### Known devices

`KnownDeviceProofEndpoints(database, cache, proofSigner, proofExpiration, expires, signals)`.  After a login,
`POST establish2` returns a `KnownDeviceSecretAndExpiration`; the client stores the secret.  On later logins
`POST prove` with that secret yields a strength-3 proof, enough to turn "password, then a second factor" into
"password on a device we've seen" if you set the required strength to 13.  Secrets last `expires` (default 30 days).

### Backup codes

`BackupCodeEndpoints(database, cache, …)`.  `POST reset-codes` returns fresh single-use codes (replacing any old ones),
`POST clear-codes` removes them, and `GET established` reports whether the caller has any.  `POST prove` takes
`IdentificationAndPassword` with a code as `password`; dashes and case are ignored.  A code stands in for any one
strength-10 factor.  Only hashes are stored, and a used code is kept, marked used.

### Passkeys (WebAuthn)

`WebAuthNProofEndpoints` in `sessions-webauthn` requires `rpId` (your registrable domain), `allowedOrigins` (an
explicit allowlist, including native-app origins), and `registrationForUser` (creation options per user).  Users
register with `register-start` / `register-finish` and log in with `start` / `prove`.  `challengeExpiration` (default 5
minutes) bounds each ceremony.  The default verifier performs no attestation checks; pass a strict `WebAuthnManager`
to enforce an authenticator allowlist.

### OAuth

Sign in with Google, Apple, Microsoft, GitHub, or any OAuth 2.0 provider: see [OAuth](oauth.md).

## Sessions and tokens

`AuthEndpoints<SUBJECT, ID>(principal, database, cache, tokenFormat, tokenInvalidationChecks)` extends
`SessionManager`.  Use `SessionManager` alone when something other than proofs creates sessions; call its
`newSession(subjectId, ...)` and hand the client the returned `RefreshToken`.

Besides the three methods above, you can override:

| Method | Default | |
|---|---|---|
| `permitAuthentication(subject)` | `true` | `false` blocks new logins and refreshes with 403, e.g. for banned accounts. |
| `permitScopes(subject)` | root | Caps every session's scopes, applied each time an access token is minted. |

Endpoints, relative to where you mount it:

| Endpoint | Auth | |
|---|---|---|
| `POST login` | none | `List<Proof>` → `IdAndAuthMethods` |
| `POST login2` | none | `LogInRequest(proofs, label, scopes, expires)`: a labeled or narrowed session |
| `POST proofs-check` | none | Like `login`, but creates nothing |
| `GET auth-requirements` | `auth:requirements` scope | The caller's options and required strength, for re-authenticating |
| `POST token/simple` | none | Refresh token → access token |
| `POST token/set-cookie` | none | Refresh token → HttpOnly cookie; see below |
| `POST token/cookie` | the cookie | Cookie's refresh token → access token |
| `GET self` | `auth:self` scope | The caller's subject |
| `POST terminate` | `auth:sessions:terminate` scope | Logs out the current session |
| `POST {sessionId}/terminate` | `auth:sessions:terminate` scope | Logs out another of the caller's sessions |
| `sessions` | `auth:sessions` scope | REST listing of the caller's sessions |

Access tokens are read from the `Authorization` header (`Bearer` optional).  A WebSocket connect may instead put
the access token in an `Authorization` or `jwt` query parameter, since browsers can't set headers on one; any other
request with a token in its URL gets a 400, since URLs end up in logs.  Refresh tokens are not credentials: only the
`token/...` endpoints take them, and sending one as `Authorization` gets a 401.  For legacy clients that do,
`acceptRefreshTokenAsBearer = Runtime { true }` turns that back on, except for a session whose refresh token
rotates (below); each such use writes to the database.

### Token formats

| `tokenFormat` | Token | |
|---|---|---|
| `PrivateTinyTokenFormat()` (default) | `w/<principal>/<base64>` | Encrypted; the client can't read it |
| `PublicTinyTokenFormat()` | `tw/<principal>/<base64>` | Signed, not encrypted |
| `JwtTokenFormat()` | HS512 JWT | For other systems that read JWTs |

They live in `com.lightningkite.lightningserver.sessions.token`.  All default to a 5-minute `expiration` and derive
their keys from `secretBasis`, so changing `secretBasis` logs everyone out.  A JWT's `prn` header is the principal
name, `sub` the subject ID, `sid` the session, `iat` when the token was minted, and `siat` when the user authenticated.
`JwtTokenFormat`'s `issuer` and `audience` default to the server's public URL, and tokens that don't match are
rejected.

```kotlin
val auth = path.path("auth") module object : AuthEndpoints<Account, Uuid>(
    principal = AccountAuth,
    database = database,
    cache = cache,
    tokenFormat = Runtime { JwtTokenFormat() },
) { /* ... */ }
```

### Keeping a web client's refresh token in a cookie

A refresh token in `localStorage` can be read by any script on the page.  A web client can hand it to the server
instead:

1. Log in with `login2` as usual.
2. Send the refresh token to `POST token/set-cookie` (`setRefreshTokenCookie`), then forget it.  The response sets the
   `RefreshToken` cookie: `HttpOnly`, `Secure`, `SameSite=Strict`, with its `Path` limited to `token/cookie`, so no
   other request carries it.  It expires with the session.
3. Get each access token from `POST token/cookie` (`getTokenFromCookie`), which needs no body.  A 401 means logged out.

The cookie's refresh token rotates.  `token/set-cookie` stores the session's next token rather than the one sent,
which passed through page scripts.  Each `token/cookie` call replaces the cookie with the next one.  For 60 seconds
after a rotation, the token it replaced still works and gets the current one back, in case a response was lost or
another tab's request crossed it.  After that, using it means someone copied it, and terminates the session.  Older
tokens are simply refused.  A rotating session's tokens don't work at `token/simple` or as a bearer token.  Each
token is an HMAC of the one before it with a key derived from `secretBasis`; after changing `secretBasis` the
replaced token is refused, but the current one keeps working.  `rotateRefreshToken(token, request)` is the same
exchange, for other endpoints that rotate.

`POST terminate` also deletes the cookie.  The cookie is only sent same-site, and a cross-origin client must send
requests with credentials, which needs CORS to allow them.  `token/set-cookie` accepts only a JSON body, so a
cross-site form can't plant a session.  For local development over plain http, where native clients and Safari won't
send a `Secure` cookie, pass `refreshTokenCookieSecure = Runtime { false }`.

### Sub-sessions

`newSubSession(parent, label, scopes, expires)` returns a new session derived from the one behind the `Authentication`
`parent`, and its refresh token, for handing narrower access to another app or device:

- `scopes` must be within the parent's (`ForbiddenException` otherwise).  The default, root, means "the same as the
  parent".
- It expires no later than the parent and inherits the parent's authentication time, so it can't pass a `maxAge` check
  the parent couldn't.
- Terminating the parent terminates it, and its own sub-sessions.

It is a server-side call with no endpoint: one would let anyone holding a stolen access token mint a long-lived refresh
token.  For access that must outlive the user's login (a long-running integration), create a separate session instead.

### Logging out and revoking access

Terminating a session (through the endpoints above, or by setting `terminated` through `sessionInfo.table()`) stops its
refresh token and every session derived from it.  Writes through `sessionInfo.baseTable()` or straight to the database
don't cascade.  Sessions are never deleted; the row remains as an audit record of its IPs and user agents.

Access tokens are self-contained, so by default one stays valid until it expires, up to 5 minutes after logout.  To
reject them immediately, turn on `tokenInvalidationChecks`:

```kotlin
val auth = path.path("auth") module object : AuthEndpoints<Account, Uuid>(
    principal = AccountAuth,
    database = database,
    cache = cache,
    tokenInvalidationChecks = true,
) { /* ... */ }

// After changing what an account may do, make its clients pick up the change now:
auth.invalidateSubject(account._id)
```

With it on, terminating a session also rejects its live access tokens and those of its sub-sessions.
`invalidateSession(sessionId)` and `invalidateSubject(subjectId)` reject the current access tokens of one session or of
every session of one subject, without logging anyone out: the client's next refresh gets a fresh token.  The cost is
one or two cache reads per request, and requests fail if the cache does.  Use a shared cache across instances.

## Auth events

`sessions` reports sessions created, terminated, and rejected, proofs issued, accepted, and rejected, and changes
to a user's login factors, as `AuthEvent`s.  Install an `AuthEventListener` to receive them:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ProofSessionSamples.kt#auth-listener -->
```kotlin
object AuditedLoginServer : ServerBuilder() {
    val failedLogins = install(object : AuthEventListener {
        context(runtime: ServerRuntime)
        override suspend fun onEvent(event: AuthEvent) {
            if (event is AuthEvent.ProofCredentialRejected)
                runtime.logger.warn { "Rejected ${event.method.via} proof for ${event.principal}" }
        }
    })

    // In a real app, email the user here, so they hear about changes they did not make.
    val factorNotices = install(object : AuthEventListener {
        context(runtime: ServerRuntime)
        override suspend fun onEvent(event: AuthEvent) {
            if (event is AuthEvent.FactorChanged)
                runtime.logger.info { "${event.method.via} ${event.change} for ${event.principal}" }
        }
    })
}
```

`AuthEvent.FactorChanged` is raised after a password is set, changed, or disabled, a TOTP authenticator is confirmed,
replaced, or disabled, a passkey or known device is added or disabled, or backup codes are reset or cleared, whether
through the proof endpoints, their REST endpoints, or your own writes through their `modelInfo`.  `change` is
`Added`, `Replaced`, or `Removed`.  Nothing tells the user about these unless you install a listener that does, and
you should: an unexpected change is how a user learns someone else is in their account.  Email and phone number
changes are edits to your own user model, so watch those with its signals.

A listener that throws is logged and doesn't affect the request, or undo the change it reports.  The `audit` module's `AuthEventLog` records every
event to the database.

## Security notes

- A proof can create only one session; reusing it fails with `proof-already-used` (400).
- Refresh-token secrets and device secrets are stored hashed.  A leaked refresh token is a full login until its session
  expires, goes stale, or is terminated, so clients should keep it in secure storage.
- `login` errors distinguish "no such user" (`no-single-user`) from a bad proof.  If your proofs don't auto-create
  accounts, that reveals which identifiers exist; screen with `Suppress` and rate-limit accordingly.
- Use a shared cache in production: PINs, rate limits, used-proof markers, and OAuth state live there.

## See also

- [Authentication](auth.md): requirements, scopes, `maxAge`, and testing with `testAuth`
- [OAuth](oauth.md)
- [Email](email.md): configuring the email service
- [Encryption](encryption.md): `secretBasis`, signing, and password hashing
- [Rate limiting](rate-limiting.md)
