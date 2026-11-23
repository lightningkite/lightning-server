# Email and SMS

`EmailService` and `SMS` come from Service Abstractions.  Declare each as a setting; the URL in `settings.json` picks
the provider (console, SMTP, Mailgun, Twilio, AWS), so code never imports a provider class.

For sign-in by emailed or texted codes, see [Proof & session authentication](proof-session.md).  For per-user,
scheduled, batched messages, see [Notifications](notifications.md).

## Minimal example

<!-- sample: com/lightningkite/lightningserver/guide/samples/EmailSamples.kt#email-imports -->
```kotlin
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.data.*
import com.lightningkite.services.email.*
import com.lightningkite.services.sms.*
import kotlinx.html.*
import kotlin.test.*
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/EmailSamples.kt#email-server -->
```kotlin
object MailServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val email = setting("email", EmailService.Settings())
    val sms = setting("sms", SMS.Settings())

    val sendWelcome = path.path("welcome").post bind ApiHttpHandler(
        summary = "Send welcome email",
        auth = noAuth,
        implementation = { to: EmailAddress ->
            email().send(
                Email(
                    subject = "Welcome!",
                    to = listOf(EmailAddressWithName(to)),
                    plainText = "Thanks for signing up.",
                )
            )
        }
    )

    val sendCode = path.path("code").post bind ApiHttpHandler(
        summary = "Text a code",
        auth = noAuth,
        implementation = { to: PhoneNumber ->
            sms().send(to, "Your code is 123456")
        }
    )
}
```

Both settings default to `console`, which prints each message instead of sending it.  `email()` and `sms()` resolve
the live service, so call them only inside a handler, task, or test.

## Testing

The `test` backends keep messages in memory.

<!-- sample: com/lightningkite/lightningserver/guide/samples/EmailSamples.kt#email-test -->
```kotlin
fun mailTest() = MailServer.testBlocking(settings = {
    email set EmailService.Settings("test")
    sms set SMS.Settings("test")
}) {
    MailServer.sendWelcome.test(null, "jane@example.com".toEmailAddress())
    val sent = (MailServer.email() as TestEmailService).lastEmailTo("jane@example.com")
    assertEquals("Welcome!", sent?.subject)

    MailServer.sendCode.test(null, "+15551234567".toPhoneNumber())
    val text = (MailServer.sms() as TestSMS).lastMessageSent
    assertEquals("Your code is 123456", text?.message)
}
```

`TestEmailService` also has `sentEmails`, `lastEmail()`, `emailsTo(address)`, `lastEmailWithSubject(subject)` and
`clear()`.  `TestSMS` has `messageHistory` and `reset()`.

## HTML email

Pass a [kotlinx.html](https://github.com/Kotlin/kotlinx.html) builder as `html`; a plain-text part is derived from it.

<!-- sample: com/lightningkite/lightningserver/guide/samples/EmailSamples.kt#email-html -->
```kotlin
fun orderConfirmation(to: EmailAddress): Email = Email(
    subject = "Order confirmed",
    from = EmailAddressWithName("orders@example.com", "Example Store"),
    to = listOf(EmailAddressWithName(to)),
    bcc = listOf(EmailAddressWithName("audit@example.com")),
    html = {
        body {
            h1 { +"Thanks for your order" }
            p { +"Your total is "; b { +"$42.00" }; +"." }
        }
    },
)
```

Pass `plainText` alongside `html` to write the text part yourself.  With only `plainText`, the HTML part is the text
wrapped in `<pre>`.  The constructor taking `html` as a `String` is deprecated.

`from` is optional; without it the provider's default sender is used (for SMTP, the URL's `fromEmail`).

## Attachments

<!-- sample: com/lightningkite/lightningserver/guide/samples/EmailSamples.kt#email-attachment -->
```kotlin
fun invoiceEmail(to: EmailAddress, pdf: ByteArray): Email = Email(
    subject = "Your invoice",
    to = listOf(EmailAddressWithName(to)),
    plainText = "Your invoice is attached.",
    attachments = listOf(
        Email.Attachment(
            inline = false,
            filename = "invoice.pdf",
            typedData = TypedData.bytes(pdf, MediaType.Application.Pdf),
        )
    ),
)
```

`inline = true` embeds the file for reference from the HTML (`cid:`) instead of listing it as a download.

## Bulk sends with substitutions

`sendBulk` fills `{{name}}` placeholders in the subject, HTML, and text of a template, once per recipient.

<!-- sample: com/lightningkite/lightningserver/guide/samples/EmailSamples.kt#email-bulk -->
```kotlin
fun bulkMailTest() = MailServer.testBlocking(settings = { email set EmailService.Settings("test") }) {
    val template = Email(
        subject = "Hello {{name}}",
        to = listOf(),
        plainText = "Hi {{name}}, your code is {{code}}.",
    )
    MailServer.email().sendBulk(
        template,
        listOf(
            EmailPersonalization(
                to = listOf(EmailAddressWithName("alice@example.com")),
                substitutions = mapOf("name" to "Alice", "code" to "111222"),
            ),
            EmailPersonalization(
                to = listOf(EmailAddressWithName("bob@example.com")),
                substitutions = mapOf("name" to "Bob", "code" to "333444"),
            ),
        )
    )

    val bob = (MailServer.email() as TestEmailService).lastEmailTo("bob@example.com")
    assertEquals("Hello Bob", bob?.subject)
    assertEquals("Hi Bob, your code is 333444.", bob?.plainText)
}
```

Substituted values are inserted as-is, HTML included: escape user-supplied values yourself.  `sendBulk(emails)` sends
a list of finished emails.  The default implementations send concurrently and cancel the rest if one fails.

## Providers

| Service | URL | Module (`sa.` catalog alias) | Register by referencing |
|---|---|---|---|
| Email | `console` (default), `test` | `email` | (built in) |
| Email | `smtp://user:pass@host:port?fromEmail=noreply@example.com&fromLabel=My App` | `email-javasmtp` | `JavaSmtpEmailService` |
| Email | `mailgun://apiKey@mg.example.com` | `email-mailgun` | `MailgunEmailService` |
| SMS | `console` (default), `test` | `sms` | (built in) |
| SMS | `twilio://accountSid:authToken@+15551234567` | `sms-twilio` | `TwilioSMS` |
| SMS | `awssms://accessKey:secretKey@us-west-2?originationIdentity=...` (keys optional) | `sms-aws` | `AwsSms` |

Provider modules register their URL scheme when their companion object loads, so reference it before settings are
read:

```kotlin
import com.lightningkite.services.email.javasmtp.JavaSmtpEmailService
import com.lightningkite.services.sms.twilio.TwilioSMS

object Server : ServerBuilder() {
    val email = setting("email", EmailService.Settings())
    val sms = setting("sms", SMS.Settings())

    init {
        JavaSmtpEmailService
        TwilioSMS
    }
}
```

```json
{
  "email": "smtp://apikey:SG.xxxx@smtp.sendgrid.net:587?fromEmail=noreply@example.com&fromLabel=My App",
  "sms": "twilio://ACxxxx:token@+15551234567"
}
```

The full URL grammar for each provider is in Service Abstractions'
[settings URL reference](https://github.com/lightningkite/service-abstractions/blob/main/docs/settings-url-reference.md).
[Services & settings](services.md) covers settings in general.

## Gotchas

- **SMTP `fromEmail` is required.**  The URL parses without it but fails when the service is built.  Query values are
  not URL-decoded: write a space in `fromLabel` as a literal space, not `+`.
- **Port 465 uses SSL; other ports use STARTTLS**, which is required unless the URL has `insecure=true`.
- **Health checks send real email.**  `/meta/health` checks the SMTP and Mailgun services by sending a message to
  `health-check@example.com`, at most every 6 hours.  SMS health checks send nothing.
- **Phone numbers are E.164.**  `"(555) 123-4567".toPhoneNumber()` normalizes 10-digit numbers to `+1`; anything else
  must start with `+` and a country code, or it throws.
- **SMS costs money per 160-character segment**, and most countries require opt-out text on marketing messages.
- `Email` rejects CR or LF in custom header names, values, and attachment filenames (header injection).

## Next

- [Notifications](notifications.md): email, SMS, push, and in-app messages from application events
- [Proof & session authentication](proof-session.md): sign-in by emailed or texted codes
- [Tasks](tasks.md): send slow email off the request path
- [Testing](testing.md)
