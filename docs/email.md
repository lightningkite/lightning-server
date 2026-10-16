# Email

Email's built in like many of the other service types a server can depend on.

## Declaring the need for an email sender

Add a setting as follows:

```kotlin
import com.lightningkite.services.email.EmailService

object Server : ServerBuilder() {
    //...
    val email = setting("email", EmailService.Settings())
    //...
}
```

## Sending an email

```kotlin
import com.lightningkite.services.email.Email
import com.lightningkite.services.email.EmailAddressWithName
import com.lightningkite.services.data.toEmailAddress

email().send(Email(
    subject = "Hello world",
    to = listOf(EmailAddressWithName("joseph@lightningkite.com".toEmailAddress())),
    html = "<h1>Hello world!</h1>"
))
```

## Available Backends

The default, `console`, prints emails instead of sending them, and `test` keeps them in memory for assertions in unit
tests:

```json5
// settings.json
{
  "email": { "url": "console" }
}
```

The service-abstractions email modules also register `smtp`, `ses` (Amazon SES), `sendgrid`, and `mailgun` for sending.
As with databases and caches, reference the implementation's class in your server so its URL scheme is registered.
