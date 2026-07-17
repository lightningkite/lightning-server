> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Email

Lightning Server treats email as a swappable service: you declare one `EmailService` setting in
your `ServerBuilder` and point it at any supported backend via a URL string in `settings.json`.
Application code never imports provider-specific classes.

---

## Imports

All examples in this chapter use the following imports:

```kotlin
// Illustrative — requires sessions, sessions-email, and email-javasmtp modules where noted.
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.sessions.proofs.*
import com.lightningkite.services.email.*
import com.lightningkite.services.cache.*
```

---

## Declaring the email setting

```kotlin
object Server : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())
    val email = setting("email", EmailService.Settings())
    // ...
}
```

`EmailService.Settings()` defaults to `"console"` — the built-in backend that prints every email
to standard output.  Running the server with this default is safe and produces no external traffic,
so it is the right choice for local development.

---

## Backends

The URL string stored in `settings.json` (under the key you supplied to `setting()`) determines
which backend is loaded.

| URL scheme | Description | Module |
|---|---|---|
| `console` | Prints emails to stdout. Default. | built-in |
| `test` | Collects emails in memory; no output. For automated tests. | built-in |
| `smtp://user:pass@host:port?fromEmail=...` | Sends via SMTP (Gmail, SendGrid, Office 365, …). | `email-javasmtp` |

### Registering non-default backends

The `console` and `test` schemes are always available.  `smtp://` is registered by the
`email-javasmtp` module — you must reference its companion object before settings load so the
URL scheme handler is registered.  Place the reference in an `init {}` block:

```kotlin
// Illustrative — requires email-javasmtp module.
import com.lightningkite.services.email.javasmtp.JavaSmtpEmailService

object Server : ServerBuilder() {
    val email = setting("email", EmailService.Settings())

    init {
        JavaSmtpEmailService  // registers "smtp://"
    }
}
```

### SMTP URL format

```
smtp://username:password@host:port?fromEmail=noreply@example.com&fromLabel=My+App
```

| Parameter | Description |
|---|---|
| `fromEmail` | (required) Default sender address |
| `fromLabel` | (optional) Display name; defaults to the project name |

Port 465 enables SSL; port 587 uses STARTTLS.  Gmail requires an app-specific password when
two-factor authentication is enabled.

### settings.json examples

```json
// Development
{ "email": "console" }

// SendGrid via SMTP
{ "email": "smtp://apikey:SG.xxxx@smtp.sendgrid.net:587?fromEmail=noreply@example.com&fromLabel=My+App" }

// Gmail
{ "email": "smtp://me@gmail.com:app-password@smtp.gmail.com:587?fromEmail=me@gmail.com&fromLabel=My+App" }
```

---

## Sending an email

Access the live service by calling the setting inside a handler or other `ServerRuntime` context:

```kotlin
// Illustrative — plain-text email.
val sendWelcome = path.path("welcome").post bind ApiHttpHandler(
    auth = noAuth,
    summary = "Send welcome email",
    errorCases = emptyList(),
    successCode = HttpStatus.OK,
    implementation = { input: String ->   // input = recipient address
        email().send(
            Email(
                subject = "Welcome!",
                to = listOf(EmailAddressWithName(input)),
                plainText = "Thanks for signing up."
            )
        )
    }
)
```

`email()` resolves the live `EmailService` from the running runtime.  Never call `email()` at
module-load time (top-level initializer); that would crash because no runtime exists yet.

### Building the Email object

`Email` has two non-deprecated constructors:

**Plain-text email** — provide `plainText`:

```kotlin
Email(
    subject = "Your receipt",
    to = listOf(EmailAddressWithName("customer@example.com", "Jane Smith")),
    plainText = "Your order total is $42.00.\nThank you for your purchase."
)
```

**HTML email** — provide an `html: HTML.() -> Unit` lambda (uses kotlinx.html):

```kotlin
// Illustrative — requires kotlinx.html dependency.
import kotlinx.html.*

Email(
    subject = "Your receipt",
    to = listOf(EmailAddressWithName("customer@example.com")),
    html = {
        body {
            h1 { +"Order Confirmed" }
            p { +"Your order total is "; b { +"$42.00" }; +"." }
        }
    }
)
```

When you supply only `html`, a plain-text fallback is generated automatically by stripping HTML
tags.  When you supply only `plainText`, a minimal `<pre>`-wrapped HTML body is generated.

**Common optional fields:**

```kotlin
Email(
    subject = "Quarterly report",
    from = EmailAddressWithName("reports@example.com", "Reports Team"),
    to = listOf(EmailAddressWithName("manager@example.com")),
    cc = listOf(EmailAddressWithName("cfo@example.com")),
    bcc = listOf(EmailAddressWithName("audit@example.com")),
    plainText = "See attached report.",
    attachments = listOf(
        Email.Attachment(
            inline = false,
            filename = "report.pdf",
            typedData = TypedData(pdfBytes, MediaType.Application.Pdf)
        )
    )
)
```

### EmailAddressWithName

```kotlin
EmailAddressWithName("user@example.com")                    // address only
EmailAddressWithName("user@example.com", "Jane Smith")      // with display name
```

---

## Sending bulk email with template substitution

`sendBulk` accepts a template `Email` and a list of `EmailPersonalization` records.
Use `{{variableName}}` placeholders in the subject, HTML, and plain-text bodies.  Each
personalization replaces those placeholders for a specific recipient:

```kotlin
// Illustrative.
val template = Email(
    subject = "Hello {{name}}!",
    to = listOf(),   // overridden per personalization
    plainText = "Hi {{name}}, your verification code is {{code}}."
)

val personalizations = listOf(
    EmailPersonalization(
        to = listOf(EmailAddressWithName("alice@example.com")),
        substitutions = mapOf("name" to "Alice", "code" to "111222")
    ),
    EmailPersonalization(
        to = listOf(EmailAddressWithName("bob@example.com")),
        substitutions = mapOf("name" to "Bob", "code" to "333444")
    )
)

email().sendBulk(template, personalizations)
```

The default `sendBulk` implementation fans out concurrently using structured coroutines — if one
recipient fails, the others are cancelled.  Override `sendBulk` on a custom `EmailService` if you
need sequential behaviour.

---

## Magic-link and PIN login emails

The `sessions-email` module provides `EmailProofEndpoints`, a proof method that sends a
six-character PIN to an email address.  The user enters the PIN back into the app to prove
ownership and earn a signed `Proof` that can be exchanged for a session.

See [Proof & Session Authentication](../guide/proof-session.md) for the full proof-session model.
The wiring below is illustrative; it requires the `sessions`, `sessions-email`, and
`email-javasmtp` (or similar) modules.

```kotlin
// Illustrative — requires sessions and sessions-email modules.
object Server : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())
    val email = setting("email", EmailService.Settings())

    // PinHandler stores short-lived PINs in the cache.
    // The second argument is a key prefix — keep it unique per proof method.
    val pins = PinHandler(cache, "email-pins")

    // Mount the proof method at any path you choose.
    // The emailTemplate lambda receives (recipientAddress, pinCode) and returns an Email.
    val proofEmail = path.path("proof").path("email") module EmailProofEndpoints(
        pin = pins,
        email = email,
        emailTemplate = { to, pin ->
            Email(
                subject = "Your login code",
                to = listOf(EmailAddressWithName(to)),
                plainText = "Your login code is $pin. It expires in 15 minutes."
            )
        }
    )

    // ...auth endpoints that accept the resulting Proof...
}
```

`EmailProofEndpoints` exposes two typed API endpoints:

| Endpoint | Body | Response |
|---|---|---|
| `POST .../start` | `String` (email address) | `String` (lookup key) |
| `POST .../prove` | `FinishProof { key, password }` | `Proof` |

The `password` field in `FinishProof` is the PIN the user received by email.  The returned
`Proof` is then posted to `/auth/login` to open a session.

### PIN configuration

`PinHandler` accepts several constructor parameters for tuning:

| Parameter | Default | Description |
|---|---|---|
| `cache` | — | Cache setting for storing in-flight PINs |
| `keyPrefix` | — | Unique string to namespace PIN cache keys |
| `availableCharacters` | `A-Z` minus `I` and `O` | Characters used in generated PINs |
| `length` | `6` | PIN length |
| `expiration` | `15 minutes` | How long a PIN remains valid |
| `maxAttempts` | `5` | Incorrect guesses before the PIN is invalidated |

### Optional: email address allow-listing

Pass a `verifyEmail` lambda to reject certain addresses before sending:

```kotlin
// Illustrative.
EmailProofEndpoints(
    pin = pins,
    email = email,
    emailTemplate = { to, pin -> /* ... */ },
    verifyEmail = { address ->
        // return false to silently drop the send (no error exposed to caller)
        !address.endsWith("@disposable-provider.example")
    }
)
```

### Advanced: embedding a proof in a magic link

`EmailProofEndpoints` exposes a `send(destination) { proof -> Email }` overload for embedding a
signed `Proof` directly in the email (magic link pattern):

```kotlin
// Illustrative — call inside a ServerRuntime context (e.g., a scheduled task or endpoint handler).
Server.proofEmail.send(userEmail) { proof ->
    Email(
        subject = "Click to log in",
        to = listOf(EmailAddressWithName(userEmail)),
        plainText = "Log in: https://example.com/auth?proof=${proof.encodeToString()}"
    )
}
```

The proof is cryptographically signed; forging a link is not possible without the server's secret
key.

---

## Testing

In tests, override the email setting to `"test"` and cast the service to `TestEmailService` to
inspect sent emails:

```kotlin
// Illustrative — requires sessions-email module.
Server.testBlocking(settings = { email set EmailService.Settings("test") }) {
    val emailService = Server.email() as TestEmailService

    // trigger some action that sends an email
    Server.someEmailTrigger.test(null, Unit)

    val sent = emailService.lastSent
    check(sent != null)
    check(sent.subject == "Your login code")
}
```

`TestEmailService` collects every `send()` call in memory and exposes the last sent message via
`lastSent`.  Each `testBlocking` call gets a fresh instance — no shared state between tests.

---

## What's next

- **SMS & PIN login** — send PIN codes via text message (`SmsProofEndpoints`)
- **Proof & Session** — how proofs accumulate into sessions
- **Notifications** — batched email delivery via the notification dispatcher
