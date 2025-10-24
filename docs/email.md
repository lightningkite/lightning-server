# Email

Last updated January 2025 (`version-5`)

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
import com.lightningkite.toEmailAddress

email().send(Email(
    subject = "Hello world",
    to = listOf(EmailAddressWithName("joseph@lightningkite.com".toEmailAddress())),
    html = "<h1>Hello world!</h1>"
))
```
