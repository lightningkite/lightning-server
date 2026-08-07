# Proof & Session Authentication

This page explains how Lightning Server turns raw user evidence ("I know this PIN") into a durable
session, and how that session becomes an `Authentication<SUBJECT>` token your endpoint handlers
can read.

See [Authentication & Sessions](auth.md) for the `PrincipalType` / `UserAuth` / `AuthRequirement`
pattern that governs what happens *inside* a handler once a token arrives.

---

## The model at a glance

```
User ──► one or more proofs ──► total strength ≥ threshold ──► session created ──► bearer token
```

A **proof** is a signed, time-limited assertion that a user has verified some property of their
identity (e.g. "this person controls the email address alice@example.com").  Each proof carries:

- A **property** — the identity attribute it verifies (e.g. `"email"`, `"phone"`, `"password"`).
- A **strength** integer — how trustworthy this evidence is (configured on the `ProofMethod`).
- A **signature** — a server-side HMAC over the proof data, validated at session creation time.

Proofs **accumulate**:

- The server sums the *strongest* proof per unique property.
- If the total meets `requiredProofStrengthFor(subject)`, a session is created.
- Stacking multiple proofs for the *same* property does not increase strength — only the
  highest-strength proof per property is counted.

```
// Illustrative: per-property max prevents gaming the system.
// property "email" → strengths [3, 5]  →  contributes 5
// property "phone" → strengths [3]     →  contributes 3
// total = 8; if threshold = 5 ──► session issued
```

---

## Proof methods

Each proof method is an object that mounts its own endpoints on a path you choose.  You include it
in a `ServerBuilder` with the `module` infix.

| Class | Module | Mechanism |
|---|---|---|
| `EmailProofEndpoints` | `sessions-email` | Two-step: sends a PIN by email; user submits key + PIN |
| `SmsProofEndpoints` | `sessions-sms` | Two-step: sends a PIN by SMS; user submits key + PIN |
| `PasswordProofEndpoints` | `sessions` | One-step: verifies a stored password hash |
| `TimeBasedOTPProofEndpoints` | `sessions` | One-step: validates a TOTP code from an authenticator app |
| `KnownDeviceProofEndpoints` | `sessions` | One-step: validates a device token issued on a prior login |
| `WebAuthNProofEndpoints` | `sessions` | Two-step: WebAuthn/passkey challenge → assertion flow |
| `BackupCodeEndpoints` | `sessions` | One-step: single-use backup recovery codes |
| `OauthProofEndpoints` | `sessions-oauth` | External: redirects to OAuth provider; resume URL returns a `Proof` |

Two-step methods (`StartedProofMethod`) expose a `start` endpoint and a `prove` endpoint.
One-step methods (`DirectProofMethod`) expose only a `prove` endpoint.  OAuth methods
(`ExternalProofMethod`) expose a `start` endpoint that redirects; the returned `Proof` is then
posted to the session exchange endpoint.

---

## Wiring: a complete example

The following is illustrative, verified against the demo server and session module source.  It
requires the `sessions`, `sessions-email`, `sessions-sms`, and `sessions-oauth` modules as
Gradle dependencies — not available for compiled samples in this module.

```kotlin
// Illustrative — requires sessions / sessions-email / sessions-sms / sessions-oauth modules.
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache    = setting("cache",    Cache.Settings())
    val email    = setting("email",    Email.Settings())
    val sms      = setting("sms",      Sms.Settings())

    // PinHandler manages the short-lived PINs used by email and SMS proof methods.
    val pins = PinHandler(cache, "pins")

    // Mount one proof method per path segment you choose.
    val proofEmail    = path.path("proof").path("email")    module EmailProofEndpoints(pins, email) { to, pin ->
        Email(
            subject = "Log In Code",
            to = listOf(EmailAddressWithName(to)),
            plainText = "Your PIN is $pin.",
        )
    }
    val proofPhone    = path.path("proof").path("phone")    module SmsProofEndpoints(pins, sms)
    val proofPassword = path.path("proof").path("password") module PasswordProofEndpoints(database, cache)
    val proofOtp      = path.path("proof").path("otp")      module TimeBasedOTPProofEndpoints(database, cache)
    val proofDevices  = path.path("proof").path("devices")  module KnownDeviceProofEndpoints(database, cache)

    // AuthEndpoints mounts the session exchange endpoints under a path you choose.
    // It extends SessionManager which handles token reading, /token/simple, /self, etc.
    val auth = path.path("auth") module object : AuthEndpoints<User, Uuid>(
        principal = UserAuth,       // your PrincipalType<User, Uuid>
        database  = database,
        cache     = cache,
    ) {
        context(server: ServerRuntime)
        override suspend fun requiredProofStrengthFor(subject: User): Int = 5

        context(server: ServerRuntime)
        override suspend fun sessionExpiration(subject: User): Instant? = null  // no hard expiry

        context(server: ServerRuntime)
        override suspend fun sessionStaleAfter(subject: User): Duration? = null  // no inactivity expiry
    }
}
```

### Constructor parameters

`AuthEndpoints<SUBJECT, ID>` extends `SessionManager<SUBJECT, ID>` and takes:

| Parameter | Type | Description |
|---|---|---|
| `principal` | `PrincipalType<SUBJECT, ID>` | How to fetch and serialize subjects |
| `database` | `Runtime<Database>` | For session record storage |
| `cache` | `Runtime<Cache>` | For proof replay prevention (`claimOnce`) |
| `tokenFormat` | `Runtime<TokenFormat>` | Token encoding; defaults to `PrivateTinyTokenFormat` |

### Abstract methods you must implement

**On `AuthEndpoints`:**

```kotlin
// Illustrative — abstract method you implement on your AuthEndpoints subclass.
context(server: ServerRuntime)
override suspend fun requiredProofStrengthFor(subject: SUBJECT): Int
```

Return the minimum total proof strength required for this subject to open a session.  You can vary
it per user — e.g. requiring a higher threshold for admin accounts.

**On `SessionManager` (via `AuthEndpoints`):**

```kotlin
// Illustrative — abstract methods you implement on your AuthEndpoints subclass.
context(server: ServerRuntime)
override suspend fun sessionExpiration(subject: SUBJECT): Instant?  // hard expiry; null = no expiry

context(server: ServerRuntime)
override suspend fun sessionStaleAfter(subject: SUBJECT): Duration?  // inactivity expiry; null = never
```

---

## The session exchange flow

### Step 1 — Collect a proof

Call a proof method's endpoint to generate a `Proof`:

**Two-step example (email PIN):**

```
POST /proof/email/start      body: "alice@example.com"
→ { "key": "<pin-lookup-key>" }

POST /proof/email/prove      body: { "key": "<key>", "pin": "123456" }
→ { ... Proof ... }          // signed Proof object; valid for a short window
```

**One-step example (password):**

```
POST /proof/password/prove   body: { "key": "alice@example.com", "password": "hunter2" }
→ { ... Proof ... }
```

### Step 2 — Exchange proof(s) for a session

Post the accumulated proof(s) to `/auth/login` (or `/auth/login2` with a `LogInRequest` for
multi-proof in one call):

```
POST /auth/login     body: Proof
→ { "accessToken": "...", "refreshToken": "...", ... }
```

The server:
1. Validates each proof's signature (prevents tampering).
2. Calls `claimOnce` on each proof's signature fingerprint — replay attacks are blocked.
3. Groups proofs by property, takes the maximum strength per group, sums totals.
4. Compares the total against `requiredProofStrengthFor(subject)`.
5. If the threshold is met, creates a `Session` record in the database and returns a signed
   bearer token pair (access token + refresh token).

### Step 3 — Use the bearer token

Send subsequent requests with the access token in the `Authorization` header:

```
GET /profile
Authorization: Bearer <accessToken>
```

`SessionManager` implements `Authentication.Reader<SUBJECT>`, which means the framework
calls it on every request.  It reads the header (also accepts the `Authorization` query
parameter and the `Authorization` cookie) and deserializes it into an `Authentication<SUBJECT>`
that is then available as `auth` in your handler.

When the access token expires, the client exchanges the refresh token for a new one:

```
POST /auth/token/simple    body: "<refreshToken>"
→ { "accessToken": "...", "refreshToken": "...", ... }
```

---

## Endpoints provided by `AuthEndpoints` / `SessionManager`

| Path | Description |
|---|---|
| `POST /auth/login` | Exchange a single `Proof` for a session |
| `POST /auth/login2` | Exchange a `LogInRequest` (multiple proofs) for a session |
| `POST /auth/proofs-check` | Check accumulated strength without creating a session |
| `GET /auth/auth-requirements` | Returns the available proof methods + required strength |
| `POST /auth/token/simple` | Exchange a refresh token for a fresh access token |
| `GET /auth/self` | Returns the authenticated subject |
| `POST /auth/session/terminate` | Revoke the current session |
| REST `/auth/sessions` | CRUD for the authenticated user's sessions |

The path prefix (`/auth` above) matches whatever path you used with `module`.

---

## Security notes

- **Proof replay prevention**: each proof's signature is SHA-256 fingerprinted and `claimOnce`d
  in the cache.  A proof cannot be submitted twice, even within its validity window.
- **Per-property max prevents stacking**: submitting two email proofs of strength 3 and 5 counts
  as 5, not 8.  Only different *properties* add together.
- **Refresh token secrets are hashed** before database storage — never stored in plaintext.
- **Sessions track user-agent and IP** for audit purposes; terminated sessions are soft-deleted.
- The `requiredProofStrengthFor` method receives the *subject* (the full user record), so you can
  enforce a higher threshold for sensitive accounts.

---

## Relationship to `UserAuth` and `authOptions`

`AuthEndpoints` needs a `PrincipalType<SUBJECT, ID>` — the same `UserAuth` object you define
in the `Authentication` chapter and pass to `UserAuth.require()` on your endpoints.  The two
halves of the auth system share exactly this object:

- `AuthEndpoints(principal = UserAuth, ...)` uses it to know how to fetch and serialize users
  when creating sessions.
- `UserAuth.require()` on an endpoint uses it to validate the incoming bearer token.

You do not need to register `UserAuth` separately when using `AuthEndpoints`; `SessionManager`
calls `register(principal)` in its own `init` block.

---

## Further reading

- [Authentication & Sessions](auth.md) — `PrincipalType`, `authOptions`, `auth.fetch()`, caching keys
- Demo server: `demo/src/main/kotlin/com/lightningkite/lightningserver/demo/Server.kt` — complete
  working wiring with email, SMS, TOTP, password, known-device, and OAuth proof methods
- `sessions/src/main/kotlin/.../sessions/AuthEndpoints.kt` — proof strength calculation source
- `sessions/src/main/kotlin/.../sessions/SessionManager.kt` — token reading and session lifecycle source
