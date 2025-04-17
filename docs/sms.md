# SMS

Last updated April 17, 2025 (`version-4`)

SMS's built in like many of the other service types a server can depend on.

## Setting up SMS Services

Add a setting as follows:

```kotlin
object Server {
    //...
    val sms = setting("sms", SMSSettings())
    //...
}
```
Then in add this to your settings.json file
```json
{
  "otherSettings":"...",
  "sms" : {
    "url" : "console",
    "from" : null
  }
}
```
If you are using terraform put it into your local.auto.tfvars

For development or debugging you can set it to console output the sms text to the console.

For testing you can set the url to "test". The testing TestSMSClient is similar to ConsoleSMSClient but with more options:
* You can turn off the console printing
* It stores the last message sent
* You can set a lambda for getting send events
* This is useful for Unit Tests

For live sms services we have support out of the box for the following:
* twilio
    * url pattern: "twilio://user:password@phoneNumber"


## Sending an SMS

```kotlin
Server.sms().send(
    to = 000-000-0000,
    message = "Test"
)
```
