# Email

Email's built in like many of the other service types a server can depend on.

## Declaring the need for an email sender

Add a setting as follows:

```kotlin
object Server {
    //...
    val email = setting(name = "email", default = EmailSettings())
    //...
}
```

## Sending an email

```kotlin
email().send(
    subject = "Hello world",
    to = listOf("joseph@lightningkite.com"),
    html = "<h1>Hello world!</h1>"
)
```
