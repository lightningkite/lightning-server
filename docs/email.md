# Email

Last updated April 17, 2025 (`version-4`)

Email's built in like many of the other service types a server can depend on.

## Setting up Email Services

Add a setting as follows:

```kotlin
object Server {
    //...
    val email = setting(name = "email", default = EmailSettings())
    //...
}
```
Then in add this to your settings.json file
```json
{
  "otherSettings":"...",
  "email" : {
    "url" : "console",
    "fromEmail" : null
  }
}
```
If you are using terraform put it into your local.auto.tfvars

For development or debugging you can set it to console output the emails plain text to the console.

For testing you can set the url to "test". The testing EmailClient is similar to ConsoleEmailClient but with more options:
* You can turn off the console printing
* It stores the last message sent
* You can set a lambda for getting send events
* This is useful for Unit Tests

For live email services we have support out of the box for the following:
* mailgun
    * url pattern: "url":"mailgun://[key]@[domain]"
* smtp
    * url pattern: "url:"smtp://[username]:[password]@[host]:[port]?[params]
    * Available params are: fromEmail

## Sending an email

```kotlin
Server.email().send(
    Email(
        subject = "My first email",
        to = listOf("test@test.test","example@example.com"),
        html = "<h1>Hello email</h1>",
        plainText = "Hello email",
    )
)
```
### Email object options
```kotlin
data class Email(
    val subject: String,
    val fromEmail: String? = null,
    val fromLabel: String? = null,
    val to: List<EmailLabeledValue>,
    val cc: List<EmailLabeledValue> = listOf(),
    val bcc: List<EmailLabeledValue> = listOf(),
    val html: String,
    val plainText: String = html.emailApproximatePlainText(),
    val attachments: List<Attachment> = listOf(),
    val customHeaders: HttpHeaders = HttpHeaders.EMPTY,)
```

## Sending bulk emails

```kotlin
Server.email().sendBulk(listOf(Email(
    subject = "My first email",
    to = listOf("test@test.test","example@example.com"),
    html = "<h1>Hello email</h1>",
    plainText = "Hello email"
),        
    subject = "My first email",
    to = listOf("test@test.test","example@example.com"),
    html = "<h1>Hello email</h1>",
    plainText = "Hello email"))
```
## Sending bulk emails with personalization
```kotlin
val users = listOf(User("Bob","bob@example.com","cc@example.com"),User("John","john@example.com"))
Server.email().sendBulk(template = Email(
    subject = "Template Bulk email",
    to=listOf(),
    html = "<p> name </p>"),
personalizations = users.map { user->
    EmailPersonalization(
    to = user.email,
      cc = user.cc,
      bcc = user.bcc,
      substitutions = mapOf("name" to user.name),
      customHeaders = HttpHeaders.EMPTY)
})
```
