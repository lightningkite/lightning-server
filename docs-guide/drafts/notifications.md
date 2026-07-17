> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Push Notifications

Lightning Server's notification system is an event-driven pipeline that connects things that
happen in your application to users who want to know about them.  A single event can fan out
to push notifications, SMS, email, and in-app notifications — through the same code path,
with per-user scheduling preferences.

This page covers:

1. The `NotificationService` abstraction (for raw push sends).
2. The full `notifications` module — events, subscriptions, dispatcher, delivery scheduling.

---

## Imports

All examples in this chapter use the following imports:

```kotlin
// Illustrative — requires the notifications module and at least one push provider module
// (e.g. notifications-fcm) for production use.
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.notifications.*
import com.lightningkite.lightningserver.notifications.events.*
import com.lightningkite.lightningserver.notifications.subscriptions.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.cache.*
import com.lightningkite.services.database.*
import com.lightningkite.services.notifications.*
```

---

## The push notification service

### Declaring the setting

```kotlin
object Server : ServerBuilder() {
    val push = setting("push", NotificationService.Settings("console"))
    // ...
}
```

`NotificationService.Settings` has no built-in default — you must supply a URL string.
`"console"` is the simplest choice for local development: it prints every notification to
standard output.

### Backends

| URL scheme | Description | Module |
|---|---|---|
| `console` | Prints to stdout. | built-in |
| `test` | Collects in memory; no output. For automated tests. | built-in |
| `fcm://path/to/credentials.json` | Firebase Cloud Messaging via a service-account JSON file. | `notifications-fcm` |
| `fcm://{...json...}` | FCM with inline JSON credentials (not recommended for production). | `notifications-fcm` |

The `notifications-fcm` module must be referenced in an `init {}` block so the `fcm://` scheme
handler is registered before `settings.json` is read:

```kotlin
// Illustrative — requires notifications-fcm module.
import com.lightningkite.services.notifications.fcm.FcmNotificationClient

object Server : ServerBuilder() {
    val push = setting("push", NotificationService.Settings("console"))

    init {
        FcmNotificationClient   // registers "fcm://"
    }
}
```

### Firebase setup

FCM requires a Firebase project and a service-account credentials file:

1. In the Firebase Console, go to **Project Settings → Service Accounts**.
2. Click **Generate new private key** and download the JSON file.
3. Store the file in a secure location (not in your repository).
4. In `settings.json` point the URL at the file:

```json
{ "push": "fcm:///etc/secrets/firebase-adminsdk.json" }
```

> This block is illustrative — the exact path depends on your deployment environment.

### settings.json examples

```json
// Development
{ "push": "console" }

// Production
{ "push": "fcm:///etc/secrets/firebase-adminsdk.json" }
```

---

## Sending a push notification directly

When you need to send a push outside of the notification system (for example, a targeted alert),
call `push().send(tokens, data)` inside a `ServerRuntime` context:

```kotlin
// Illustrative — tokens is a list of FCM device registration tokens stored in your database.
val sendDirect = path.path("push").post bind ApiHttpHandler(
    auth = noAuth,
    summary = "Send direct push",
    errorCases = emptyList(),
    successCode = HttpStatus.OK,
    implementation = { tokens: List<String> ->
        val results = push().send(
            targets = tokens,
            data = NotificationData(
                notification = Notification(
                    title = "Hello",
                    body  = "This is a direct push notification.",
                )
            )
        )

        // Remove any tokens the provider reported as dead.
        val dead = results.filterValues { it == NotificationSendResult.DeadToken }.keys
        if (dead.isNotEmpty()) {
            // TODO: remove dead tokens from your user table
        }
    }
)
```

`send` returns a `Map<String, NotificationSendResult>`, one entry per token.

### NotificationData

`NotificationData` combines user-visible content with platform-specific options:

```kotlin
// Illustrative.
NotificationData(
    // User-visible content shown in the OS notification tray.
    notification = Notification(
        title    = "Order shipped",
        body     = "Your package is on its way.",
        imageUrl = "https://example.com/box.png",   // optional large image
        link     = "myapp://orders/42",             // optional deep link
    ),

    // Arbitrary key-value pairs delivered to the app (silent or alongside a visible notification).
    data = mapOf("orderId" to "42"),

    // Android-specific options.
    android = NotificationAndroid(
        channel  = "orders",                         // notification channel id (required in Android 8+)
        priority = NotificationPriority.HIGH,
        sound    = "notification.mp3",
    ),

    // iOS-specific options.
    ios = NotificationIos(
        critical = false,
        sound    = "notification.aiff",
    ),

    // Web push options.
    web = NotificationWeb(
        data = mapOf("url" to "/orders/42")
    ),

    // How long FCM should retain the message for an offline device.
    timeToLive = 24.hours,
)
```

All fields are optional; supply only what you need.

### NotificationSendResult

| Value | Meaning |
|---|---|
| `Success` | Delivered to the provider. |
| `DeadToken` | The token is expired or unregistered — remove it from your database. |
| `Failure` | Provider accepted the request but reported a non-fatal delivery failure. |

---

## The notification system

For most applications you want more than raw sends.  The `notifications` module provides an
event-driven pipeline:

```
Event fires → subscriptions determine who cares → dispatcher queues & sends
               (email / SMS / push / in-app, per user, on a schedule)
```

Three parts connect to form the pipeline:

| Part | What it does |
|---|---|
| `NotificationEndpoints` | Central coordinator; connects events to subscriptions and dispatcher |
| `Subscriptions` | Determines which users receive which events and on what schedule |
| `Dispatcher` | Stores, formats, and delivers the notifications |

### Wiring

```kotlin
// Illustrative — requires notifications module and a push/email/SMS provider.
object AppNotifications : NotificationEndpoints<User, Uuid, MyContent, MyDispatcher, NonCustomizableSubscriptions<User, Uuid>>(
    users         = Server.userInfo,    // ModelInfo<*, User, Uuid>
    dispatcher    = MyDispatcher,
    subscriptions = NonCustomizableSubscriptions(),
) {
    // Events are defined here or in companion objects, then invoked elsewhere.
}
```

Mount the whole block under a path in your main `ServerBuilder`:

```kotlin
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache    = setting("cache",    Cache.Settings())
    val push     = setting("push",     NotificationService.Settings("console"))

    val userInfo = /* your ModelInfo<*, User, Uuid> */

    val notifications = path.path("notifications") module AppNotifications
}
```

---

## Defining events

An **event** is something that happened in your application that one or more users might want to
know about.  Define events using the `event()` DSL inside (or in the context of) a
`NotificationEndpoints` instance:

```kotlin
// Illustrative — inside an object that extends NotificationEndpoints.
object AppNotifications : NotificationEndpoints<User, Uuid, String, MyDispatcher, NonCustomizableSubscriptions<User, Uuid>>(
    users         = Server.userInfo,
    dispatcher    = MyDispatcher,
    subscriptions = NonCustomizableSubscriptions(),
) {
    // Define events on your model ServerBuilder, using AppNotifications as the handler context.
}
```

```kotlin
// Illustrative — define an event from another ServerBuilder that has Server in scope.
context(builder: ServerBuilder)
val orderShipped = AppNotifications.event(
    name = "order-shipped",      // must be unique across all events
    info = Server.orderInfo,     // ModelInfo<*, Order, Uuid>
) { eventDef ->
    // Register which users should receive this event:
    eventDef.subscribed(
        email = Frequency.immediately(),
        push  = Frequency.immediately(),
        sms   = null,  // disable SMS for this event
        inApp = Frequency.immediately(),
    ) { event ->
        setOf(event.subject.customerId)  // user IDs who care about this event
    }

    // Register how to turn the event into notification content per user:
    eventDef.content { event ->
        { user ->
            "Your order #${event.subject.number} has shipped!"
        }
    }
}
```

`event()` returns an `EventLauncher<H, T, ID>`.  Call it anywhere you have a `ServerRuntime`
context to fire the event:

```kotlin
// Inside a database signal, endpoint handler, or task:
context(_: ServerRuntime)
suspend fun onOrderShipped(order: Order) {
    orderShipped(order)   // queues the event for async processing
}
```

`invoke(subject)` dispatches the event through a background task.  For synchronous handling
(useful in tests), call `handleInline(subject)` instead.

---

## Content generators

Each event must have a registered content generator.  The generator is a two-step lambda:
the outer step runs once per event (good for shared DB lookups), the inner step runs once
per interested user (for personalization):

```kotlin
// Illustrative.
eventDef.content { event ->
    // Outer: runs once, shared across all notified users.
    val order = event.subject   // already available in the event
    // val seller = Server.sellerTable().get(order.sellerId)  // optional extra fetch

    // Inner: runs per user.
    { user ->
        "Hi ${user.name}, order #${order.number} has shipped."
    }
}
```

The return type of the inner lambda is `CONTENT` — whatever type you declared on your
`NotificationEndpoints`.  A simple `String` works fine; use a data class with `title` and
`body` fields if you need richer content for the dispatcher to format separately per channel.

The framework validates at startup that every registered event has a content generator.
A missing generator causes a startup error listing the event names.

---

## Subscriptions

### NonCustomizableSubscriptions

All subscription logic lives in code.  Users have no control.  Use this for mandatory alerts
(security events, legal notices) or when your subscription logic is too complex to expose:

```kotlin
// Illustrative.
val subs = NonCustomizableSubscriptions<User, Uuid>()

// Then inside the event setup lambda:
eventDef.subscribed(
    email = Frequency.immediately(),
    push  = Frequency.immediately(),
    sms   = null,   // channel disabled
    inApp = Frequency.immediately(),
) { event ->
    // Return the set of user IDs who should be notified.
    setOf(event.subject.customerId)
}
```

The extension function `EventDefinition.subscribed(...)` is available when the enclosing
`NotificationEndpoints` uses `NonCustomizableSubscriptions`.

Use `subscribedDirect { event -> List<ScheduledSendMethods<UID>> }` when different users need
different frequencies for the same event:

```kotlin
// Illustrative — customer gets push, seller gets a daily email digest.
eventDef.subscribedDirect { event ->
    listOf(
        ScheduledSendMethods(
            user  = event.subject.customerId,
            email = null,
            push  = Frequency.immediately(),
            sms   = null,
            inApp = Frequency.immediately(),
        ),
        ScheduledSendMethods(
            user  = event.subject.sellerId,
            email = Frequency.daily(9, 0),
            push  = null,
            sms   = null,
            inApp = Frequency.immediately(),
        ),
    )
}
```

### FrequencyCustomizableSubscriptions

The server decides who is interested; users can adjust when they are notified (immediately,
daily digest, weekly, etc.) via a REST API that is auto-generated at `/subscriptions/rest`.

```kotlin
// Illustrative — requires the correct ModelInfo for NotificationSendMethods storage.
val subs = FrequencyCustomizableSubscriptions<User, Uuid>(
    info         = Server.database.modelInfo<User, NotificationSendMethods<Uuid>, UserEventType<Uuid>>(
        auth        = Server.auth,
        permissions = { ModelPermissions(read = condition { it.user eq auth.id }) }
    ),
    defaultEmail = Frequency.batch(60),   // hourly by default
    defaultPush  = Frequency.immediately(),
)

// Inside the event setup lambda (when SUBS = FrequencyCustomizableSubscriptions):
eventDef.subscribed(
    defaultEmail = Frequency.batch(60),
    defaultSms   = null,
    defaultPush  = Frequency.immediately(),
    defaultInApp = Frequency.immediately(),
) { event ->
    setOf(event.subject.customerId)
}
```

The `defaultEmail` / `defaultPush` / etc. parameters are the frequencies applied when the user
has not overridden their preference.

### FullyCustomizableSubscriptions

Users control both *which* events they receive (via filter conditions on the event subject) and
*when*.  All preferences are stored in the database and managed through an auto-generated REST
API.  See `FullyCustomizableSubscriptions` and `FullyCustomizableSubscriptionsWithAuth` in the
source for constructor details.

Use `EventDefinition.defaultSubscription(behavior) { user -> FullEventSubscription(...) }` to
specify the default subscription new users receive when they first appear.

---

## Delivery scheduling

`Frequency` controls when a notification channel sends.  A `null` frequency disables the channel
entirely for that subscriber.

```kotlin
Frequency.immediately()                                    // send as soon as the event fires
Frequency.delayed(30.minutes)                              // send 30 minutes after the event
Frequency.batch(15)                                        // batch into the next 15-minute window
Frequency.daily(hour = 9, minute = 0)                     // daily digest at 9 AM (system timezone)
Frequency.daily(9, 0, TimeZone.of("America/New_York"))    // daily digest at 9 AM Eastern
Frequency.weekly(DayOfWeek.MONDAY, 9, 0)                  // weekly digest, Monday mornings
Frequency.immediately().delayed(5.minutes)                 // immediate + 5-minute grace period
```

The dispatcher's scheduler checks for unsent notifications every minute (configurable via
`refreshSchedule`) and dispatches any whose `sendAt` has passed.

---

## The dispatcher (NotificationBulkDispatcher)

`NotificationBulkDispatcher` is an abstract class that handles storing, scheduling, and sending
notifications.  Extend it to provide your application-specific contact information and content
formatting:

```kotlin
// Illustrative — CONTENT = String for simplicity; use a data class in a real app.
object MyDispatcher : NotificationBulkDispatcher<User, Uuid, String>(
    info              = Server.database.modelInfo<HasId<*>, Notification<Uuid, String>, Uuid>(
        auth        = Server.auth,
        permissions = { ModelPermissions(read = condition { it.user eq auth.id }) }
    ),
    cache             = Server.cache,
    database          = Server.database,
    users             = Server.userInfo,
    contentSerializer = String.serializer(),
    email             = Server.email,       // optional; omit if no email channel
    sms               = Server.sms,         // optional; omit if no SMS channel
    push              = Server.push,        // optional; omit if no push channel
) {
    // Return the user's email address, or null to skip email for this user.
    context(server: ServerRuntime)
    override suspend fun email(user: User): EmailAddress? =
        user.email?.let { EmailAddress(it) }

    // Return the user's phone number, or null to skip SMS.
    context(server: ServerRuntime)
    override suspend fun phone(user: User): PhoneNumber? =
        user.phone?.toPhoneNumberOrNull()

    // Return the user's FCM device token(s) for push notifications.
    context(server: ServerRuntime)
    override suspend fun fcmTokens(user: User): Set<String> =
        user.fcmTokens

    // Called when the push provider reports tokens as expired/unregistered.
    // Remove them from the database so they are not retried.
    context(server: ServerRuntime)
    override suspend fun onFcmTokensDead(user: User, deadTokens: Set<String>) {
        Server.userInfo.table().updateOne(
            condition { it._id eq user._id },
            modification { it.fcmTokens remove deadTokens }
        )
    }

    // Format one or more queued notifications into email(s) for this user.
    context(server: ServerRuntime)
    override suspend fun makeEmailNotifications(user: User, notifications: List<Notification<Uuid, String>>): List<Email> =
        listOf(
            Email(
                subject = if (notifications.size == 1) notifications.first().content
                          else "${notifications.size} new notifications",
                to      = listOf(EmailAddressWithName(user.email!!)),
                plainText = notifications.joinToString("\n") { it.content },
            )
        )

    // Format queued notifications into SMS message(s).
    context(server: ServerRuntime)
    override suspend fun makeSmsNotifications(user: User, notifications: List<Notification<Uuid, String>>): List<String> =
        listOf(notifications.joinToString("; ") { it.content })

    // Format queued notifications into push NotificationData objects.
    context(server: ServerRuntime)
    override suspend fun makePushNotifications(user: User, notifications: List<Notification<Uuid, String>>): List<NotificationData> =
        listOf(
            NotificationData(
                notification = Notification(
                    title = if (notifications.size == 1) notifications.first().content
                            else "${notifications.size} new notifications",
                    body  = notifications.joinToString("; ") { it.content },
                )
            )
        )
}
```

The `make*` methods support **notification bulking**: when multiple events for the same user
fire with the same `sendAt` time (e.g. both are `Frequency.daily(9, 0)`), they are passed
together in one call.  Return a list with one item to merge them; return a list with multiple
items to send them separately.

### Included endpoints

`NotificationBulkDispatcher` also mounts REST and WebSocket endpoints for the notification
table at `rest/`:

```
GET  /rest                  list notifications (paginated)
POST /rest/query            query with conditions
GET  /rest/{id}             get one notification
PUT  /rest/{id}             update (e.g., mark as read)
WS   /rest/updates          real-time updates via WebSocket
```

These endpoints use the `auth` from the `ModelInfo` you provide to enforce per-user read access.

### Automatic refresh

The dispatcher runs a scheduled task (`autoRefreshNotifications`) every minute that finds
unsent notifications whose `sendAt` has passed and dispatches them.  The schedule uses a
distributed cache lock so only one instance fires at a time in a multi-instance deployment.

You can trigger a manual refresh:

```kotlin
// Inside any ServerRuntime context.
MyDispatcher.refreshNotifications()
```

---

## Device token management

Mobile clients obtain an FCM registration token from the Firebase SDK and send it to your
server.  Store it on the user record:

```kotlin
// Illustrative — a simple endpoint that registers a device token for the authenticated user.
val registerToken = path.path("push").path("register").post bind ApiHttpHandler(
    auth          = UserAuth.require(),
    summary       = "Register FCM device token",
    errorCases    = emptyList(),
    successCode   = HttpStatus.OK,
    implementation = { token: String ->
        Server.userInfo.table().updateOne(
            condition  { it._id eq auth.fetch()._id },
            modification { it.fcmTokens add token }
        )
    }
)
```

Add a complementary `deregister` endpoint for logout flows.  When `onFcmTokensDead` is called
by the dispatcher, remove those tokens to prevent repeated failed sends.

---

## Testing

Use `SMS.Settings("test")` and `NotificationService.Settings("test")` in your test settings
block.  Cast to `TestSMS` and `TestNotificationService` to inspect what was sent:

```kotlin
// Illustrative — trigger an event and verify a notification was dispatched.
Server.testBlocking(settings = {
    database set Database.Settings("ram")
    cache    set Cache.Settings("ram")
    push     set NotificationService.Settings("test")
}) {
    runBlocking {
        val user = User(email = "test@example.com", fcmTokens = setOf("token-abc"))
        Server.userInfo.table().insertOne(user)

        // Fire the event inline (bypasses task queue for synchronous tests).
        AppNotifications.orderShipped.handleInline(
            Order(number = "42", customerId = user._id)
        )

        // Check the notification was created and marked as sent.
        val notifs = MyDispatcher.info.table().all().toList()
        check(notifs.size == 1)
        check(notifs.first().push?.sent == true)
    }
}
```

`TestNotificationService` collects every `send()` call so you can assert on titles, tokens,
and results without hitting Firebase.

---

## What's next

- **SMS & PIN login** — using SMS for proof-based authentication
- **Email** — email delivery via the dispatcher and `EmailService`
- **Database** — storing and querying `Notification` records and subscription preferences
