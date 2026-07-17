> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# SMS & PIN Login

Lightning Server ships a ready-made SMS-based proof method.  The client dials your server to
request a PIN; the server texts a short code to the supplied phone number; the client echoes the
code back to prove phone ownership and receives a signed `Proof` that can be exchanged for a
session.

This page covers the SMS service abstraction and the `SmsProofEndpoints` proof method.
For the broader proof-session model — how proofs accumulate into sessions, what strength means,
and how `AuthEndpoints` ties everything together — see
[Proof & Session Authentication](../guide/proof-session.md).

---

## Imports

All examples in this chapter use the following imports:

```kotlin
// Illustrative — requires sessions-sms module and a compatible SMS provider module (e.g. sms-twilio).
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.sessions.proofs.*
import com.lightningkite.services.cache.*
import com.lightningkite.services.sms.*
```

---

## The SMS service

### Declaring the setting

```kotlin
object Server : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())
    val sms   = setting("sms",   SMS.Settings())
    // ...
}
```

`SMS.Settings()` defaults to `"console"` — the built-in backend that prints every SMS to
standard output.  No external service is contacted, making it safe for local development.

### Backends

| URL scheme | Description | Module |
|---|---|---|
| `console` | Prints messages to stdout. Default. | built-in |
| `test` | Collects messages in memory; for automated tests. | built-in |
| `twilio://accountSid:authToken@+15551234567` | Sends via Twilio. The path component is your Twilio sender number (E.164). | `sms-twilio` |

The `console` and `test` schemes are always available.  `twilio://` lives in the `sms-twilio`
module.  Touch its companion object in an `init {}` block so the URL scheme handler is registered
before `settings.json` is read:

```kotlin
// Illustrative — requires sms-twilio module.
import com.lightningkite.services.sms.twilio.TwilioSMS

object Server : ServerBuilder() {
    val sms = setting("sms", SMS.Settings())

    init {
        TwilioSMS   // registers "twilio://"
    }
}
```

### settings.json examples

```json
// Development
{ "sms": "console" }

// Twilio production
{ "sms": "twilio://ACxxxxxxxxxxxxxxxxxxxxxx:your_auth_token@+15005550006" }
```

---

## Sending an SMS directly

If you need to send an ad-hoc text message from a handler (for example, an alert or a
transactional notification), call `sms().send(to, message)` inside a `ServerRuntime` context:

```kotlin
// Illustrative — phone number must be E.164 format.
import com.lightningkite.services.data.toPhoneNumber

val sendAlert = path.path("alert").post bind ApiHttpHandler(
    auth = noAuth,
    summary = "Send test SMS",
    errorCases = emptyList(),
    successCode = HttpStatus.OK,
    implementation = { phoneNumber: String ->
        sms().send(
            to      = phoneNumber.toPhoneNumber(),  // validates and wraps to PhoneNumber
            message = "This is a test alert from your app."
        )
    }
)
```

`PhoneNumber` is a value wrapper from the `service-abstractions` library.
`String.toPhoneNumber()` validates that the string is in E.164 format (`+[country][digits]`);
it throws if the format is invalid.  Phone numbers that are already normalized can also be
passed as `PhoneNumber("+15551234567")` directly.

---

## The PIN-code login flow

### Overview

`SmsProofEndpoints` implements the two-step PIN flow:

```
POST .../start   body: "+15551234567"
→ "key"          (an opaque lookup token, not the PIN)

user reads SMS: "Your code is ABCD12"

POST .../prove   body: FinishProof(key = "key", password = "ABCD12")
→ Proof          (a signed proof of phone ownership; valid for ~1 hour)
```

The `Proof` is then posted to `/auth/login` (or bundled into `/auth/login2`) to open a session.

The proof carries **strength 5**.  Set `requiredProofStrengthFor` on your `AuthEndpoints` to
at least 5 to allow phone-only login, or higher if you require a second factor.

### Wiring SmsProofEndpoints

```kotlin
// Illustrative — requires sessions and sessions-sms modules.
object Server : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())
    val sms   = setting("sms",   SMS.Settings())

    // PinHandler stores the short-lived PINs in the cache.
    // keyPrefix keeps these keys namespaced away from other cache entries.
    val pins = PinHandler(cache, "sms-pins")

    val proofPhone = path.path("proof").path("phone") module SmsProofEndpoints(
        pin = pins,
        sms = sms,
    )

    // AuthEndpoints that accepts the resulting Proof...
}
```

The default SMS template reads:

```
Your <projectName> code is <PIN>. Don't share this with anyone.
```

where `<projectName>` is resolved from `generalSettings().projectName` at runtime.

### Constructor parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `pin` | `PinHandler` | — | Manages PIN generation and validation |
| `sms` | `Runtime<SMS>` | — | The SMS service setting |
| `smsTemplate` | `suspend context(ServerRuntime) (pin: String) -> String` | `"Your $projectName code is $pin. Don't share this with anyone."` | Lambda that returns the SMS body; receives the raw PIN |
| `proofSigner` | `RuntimeDeferred<Signer>` | `secretBasis.signer("proof")` | Signs the proof; share across all proof methods if you use several |
| `proofExpiration` | `Duration` | `1.hours` | How long a produced `Proof` stays valid |
| `verifyPhone` | `suspend context(ServerRuntime) (String) -> Boolean` | `{ true }` | Optional gate; return `false` to silently skip sending |

### Customizing the SMS template

Pass a lambda via `smsTemplate` to use your own message copy:

```kotlin
// Illustrative.
SmsProofEndpoints(
    pin = pins,
    sms = sms,
    smsTemplate = { pin -> "[$pin] is your MyApp verification code." },
)
```

Keep messages under 160 characters to avoid multi-segment billing.

### Optional: blocking phone numbers

Use `verifyPhone` to reject numbers before sending.  The `start` endpoint returns a key but no
SMS is dispatched when `verifyPhone` returns `false` — this avoids leaking information about
whether a number is blocked:

```kotlin
// Illustrative — require US numbers for this application.
SmsProofEndpoints(
    pin  = pins,
    sms  = sms,
    verifyPhone = { phone ->
        // phone is already normalized to E.164 at this point
        phone.startsWith("+1") && phone.length == 12
    },
)
```

### Phone number normalization

`SmsProofEndpoints` normalizes the phone number supplied to `start` before storing it:

- Strips leading `+`, spaces, dashes, parentheses, and dots.
- Removes any extension (`x` and everything after it).
- Keeps only digits.
- Prepends `+1` for 10-digit numbers (US/Canada auto-detection).
- Re-prefixes with `+` for all others.

Examples:

| Input | Normalized |
|---|---|
| `"555-123-4567"` | `"+15551234567"` |
| `"(555) 123-4567 x99"` | `"+15551234567"` |
| `"+44 20 7946 0958"` | `"+442079460958"` |

The normalized value is what is stored in the proof's `value` field.  Make sure the `phone`
property on your user model stores phone numbers in the same normalized format, or the proof
exchanger will not be able to locate the user.

### PinHandler configuration

`PinHandler` is shared across proof methods (email and SMS can share one handler with different
`keyPrefix` strings, or each can have its own):

| Parameter | Default | Description |
|---|---|---|
| `cache` | — | Cache setting used to store in-flight PINs |
| `keyPrefix` | — | Unique string to namespace PIN cache keys |
| `availableCharacters` | `A–Z` minus `I` and `O` | Characters used in generated PINs |
| `length` | `6` | PIN length |
| `expiration` | `15 minutes` | How long a PIN remains valid before it expires |
| `maxAttempts` | `5` | Incorrect guesses before the PIN is invalidated |

---

## Testing

In tests, override the SMS setting to `"test"` and cast the live service to `TestSMS` to
inspect sent messages.  The `TestSMS.messageHistory` list collects every `send()` call:

```kotlin
// Illustrative — verify the PIN flow end-to-end.
Server.testBlocking(settings = {
    cache set Cache.Settings("ram")
    sms   set SMS.Settings("test")
}) {
    val testSms = Server.sms() as TestSMS

    // Step 1: request a PIN
    val key = Server.proofPhone.start.test(null, "+15551234567")

    // Step 2: pull the PIN from the sent SMS
    val sentMessage = testSms.messageHistory.last()
    val pin = Regex("""([A-Z0-9]{6})""").find(sentMessage.message)!!.value

    // Step 3: prove phone ownership
    val proof = Server.proofPhone.prove.test(null, FinishProof(key = key, password = pin))
    check(proof.property == "phone")
    check(proof.value    == "+15551234567")
}
```

Each `testBlocking` call creates a fresh runtime with its own `TestSMS` instance — no shared
state between tests.

`TestSMS` also exposes:

| Property / Method | Description |
|---|---|
| `messageHistory: List<Message>` | All messages sent in this runtime |
| `lastMessageSent: Message?` | The most recent message |
| `onMessageSent: ((Message) -> Unit)?` | Callback invoked on each send |
| `reset()` | Clears `messageHistory` and `lastMessageSent` |
| `printToConsole: Boolean` | Set `true` to also print to stdout |

---

## Security notes

- **Rate limiting** — `SmsProofEndpoints` uses `constrainAttemptRate` (a cache-backed token
  bucket keyed on the normalized phone number) to limit how many SMS messages a single number
  can request per time window.  Do not expose the `start` endpoint without considering SMS
  bombing costs on your Twilio account.
- **PIN expiry** — PINs expire after `PinHandler.expiration` (default 15 minutes) and are
  immediately invalidated after a successful `prove` call.
- **Max attempts** — After `PinHandler.maxAttempts` incorrect guesses, the PIN is deleted.
  The generic error message ("Incorrect PIN") does not reveal the attempt count.
- **Proof replay** — Each proof signature is claimed exactly once via `claimOnce` in the cache
  when a session is opened.  Re-submitting the same proof is rejected.

---

## What's next

- **Proof & Session** — how proofs accumulate into sessions, and how to set
  `requiredProofStrengthFor` to require a second factor alongside the phone proof
- **Email & PIN login** — the mirror of this page for email-based PIN delivery
- **Push Notifications** — sending push notifications to mobile devices
