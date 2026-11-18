# Notifications

The `notifications` module turns application events ("a shipment was sent") into per-user email, SMS, push, and
in-app notifications, each channel sent immediately or on a schedule (batched, daily, weekly).  Use it when users
should hear about things that happen; to send one message directly, use [Email and SMS](email.md) or the push
service below.

```kotlin
dependencies {
    implementation("com.lightningkite.lightningserver:notifications:$lightningServerVersion")
}
```

Four pieces fit together:

| Piece | What it does |
|---|---|
| **Event** (`handler.event(...)`) | A named thing that happens to a model, launched with the model as its subject. |
| **Subscriptions** | Decides which users hear about an event, and how often on each channel. |
| **Content** | Turns an event into each user's notification content (your own type). |
| **Dispatcher** (`NotificationBulkDispatcher`) | Stores notifications, groups them per user, formats, and sends them. |

`NotificationEndpoints` connects them.

## Minimal example

Imports:

<!-- sample: com/lightningkite/lightningserver/guide/samples/notifications/NotificationsSamples.kt#notify-imports -->
```kotlin
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.notifications.*
import com.lightningkite.lightningserver.notifications.events.event
import com.lightningkite.lightningserver.notifications.subscriptions.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.cache.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import com.lightningkite.services.email.*
import com.lightningkite.services.notifications.NotificationData
import com.lightningkite.services.notifications.NotificationService
import com.lightningkite.services.notifications.TestNotificationService
import com.lightningkite.services.sms.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlin.test.*
import kotlin.uuid.Uuid
import com.lightningkite.services.notifications.Notification as PushNotification
```

Two `Notification` classes are in play: `com.lightningkite.lightningserver.notifications.Notification` is the stored
record; the push payload's is aliased to `PushNotification` here.

The users, the model events are about, and the content type:

<!-- sample: com/lightningkite/lightningserver/guide/samples/notifications/NotificationsSamples.kt#notify-models -->
```kotlin
@Queryable
data class Customer(
    override val _id: Uuid = Uuid.random(),
    val email: EmailAddress,
    val phone: PhoneNumber? = null,
    val pushTokens: Set<String> = setOf(),
) : HasId<Uuid>

object CustomerAuth : PrincipalType<Customer, Uuid> {
    override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
    override val subjectSerializer: KSerializer<Customer> = Customer.serializer()

    context(server: ServerRuntime)
    override suspend fun fetch(id: Uuid): Customer =
        ShopServer.customers.table().get(id) ?: throw NotFoundException("No customer $id")
}

@Queryable
data class Shipment(
    override val _id: Uuid = Uuid.random(),
    val customer: Uuid,
    val trackingNumber: String,
) : HasId<Uuid>

@Serializable
data class Alert(val title: String, val body: String)
```

The server.  Creating a `Shipment` launches the event through a database signal:

<!-- sample: com/lightningkite/lightningserver/guide/samples/notifications/NotificationsSamples.kt#notify-server -->
```kotlin
object ShopServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())
    val email = setting("email", EmailService.Settings())
    val sms = setting("sms", SMS.Settings())
    val push = setting("push", NotificationService.Settings("console"))

    init { register(CustomerAuth) }

    val customers = database.modelInfo<Customer, Customer, Uuid>(
        auth = CustomerAuth.require(),
        tableName = "Customer",
        permissions = { ModelPermissions(read = Condition { it._id eq auth.id }) },
    )

    // The explicit type breaks a cycle: the signal refers to the event, whose type refers back to this.
    val shipments: ModelInfo<Customer, Shipment, Uuid> = database.modelInfo(
        auth = CustomerAuth.require(),
        tableName = "Shipment",
        permissions = { ModelPermissions(read = Condition { it.customer eq auth.id }) },
        signals = { table -> table.postCreate { ShopNotifications.shipped(it) } },
    )

    val notificationInfo = database.modelInfo<Customer, Notification<Uuid, Alert>, Uuid>(
        auth = CustomerAuth.require(),
        tableName = "Notification",
        permissions = { ModelPermissions(read = Condition { it.user eq auth.id }) },
    )

    val notifications = path.path("notifications") module ShopNotifications
}
```

The dispatcher says how to reach a user and how to format what they're sent:

<!-- sample: com/lightningkite/lightningserver/guide/samples/notifications/NotificationsSamples.kt#notify-dispatcher -->
```kotlin
object ShopDispatcher : NotificationBulkDispatcher<Customer, Uuid, Alert>(
    info = ShopServer.notificationInfo,
    cache = ShopServer.cache,
    database = ShopServer.database,
    users = ShopServer.customers,
    contentSerializer = Alert.serializer(),
    email = ShopServer.email,
    sms = ShopServer.sms,
    push = ShopServer.push,
) {
    context(server: ServerRuntime)
    override suspend fun email(user: Customer): EmailAddress? = user.email

    context(server: ServerRuntime)
    override suspend fun phone(user: Customer): PhoneNumber? = user.phone

    context(server: ServerRuntime)
    override suspend fun fcmTokens(user: Customer): Set<String> = user.pushTokens

    context(server: ServerRuntime)
    override suspend fun onFcmTokensDead(user: Customer, deadTokens: Set<String>) {
        ShopServer.customers.table().updateOneById(user._id, Modification { it.pushTokens removeAll deadTokens })
    }

    context(runtime: ServerRuntime)
    override suspend fun makeEmailNotifications(user: Customer, notifications: List<Notification<Uuid, Alert>>): List<Email> =
        listOf(
            Email(
                subject = notifications.singleOrNull()?.content?.title ?: "${notifications.size} updates",
                to = listOf(EmailAddressWithName(user.email)),
                plainText = notifications.joinToString("\n") { it.content.body },
            )
        )

    context(runtime: ServerRuntime)
    override suspend fun makeSmsNotifications(user: Customer, notifications: List<Notification<Uuid, Alert>>): List<String> =
        notifications.map { it.content.body }

    context(runtime: ServerRuntime)
    override suspend fun makePushNotifications(user: Customer, notifications: List<Notification<Uuid, Alert>>): List<NotificationData> =
        notifications.map { NotificationData(notification = PushNotification(title = it.content.title, body = it.content.body)) }
}
```

`email`, `sms`, and `push` are optional; leave one out and that channel is skipped.  Return `null` (or no tokens) to
skip a channel for one user.

The `make…Notifications` functions receive every notification due for one user on one channel at the same moment.
Return one item to merge them (the email above becomes a digest) or one per notification to send them separately.

Events, with who hears about them and what they say:

<!-- sample: com/lightningkite/lightningserver/guide/samples/notifications/NotificationsSamples.kt#notify-events -->
```kotlin
object ShopNotifications : ServerBuilder() {
    val handler = path include NotificationEndpoints(
        users = ShopServer.customers,
        dispatcher = ShopDispatcher,
        subscriptions = NonCustomizableSubscriptions(),
    )

    val shipped = handler.event("Shipment Sent", ShopServer.shipments) { event ->
        event.subscribed(sms = null) { occurrence -> setOf(occurrence.subject.customer) }
        event.content { occurrence ->
            val tracking = occurrence.subject.trackingNumber
            { _ -> Alert("Your order shipped", "Track it with $tracking.") }
        }
    }
}
```

`event(name, info)` returns an `EventLauncher`.  Calling it (`shipped(shipment)`) runs the event as a
[task](tasks.md); `shipped.handleInline(shipment)` handles it in the current execution instead.

`content` is two steps: the outer function runs once per event, so load anything shared there; the function it
returns runs once per notified user.

## Testing

<!-- sample: com/lightningkite/lightningserver/guide/samples/notifications/NotificationsSamples.kt#notify-test -->
```kotlin
fun notificationsTest() = ShopServer.testBlocking(settings = {
    database set Database.Settings("ram")
    email set EmailService.Settings("test")
    sms set SMS.Settings("test")
    push set NotificationService.Settings("test")
}) {
    val jane = Customer(email = "jane@example.com".toEmailAddress(), pushTokens = setOf("device-1"))
    execute {
        ShopServer.customers.table().insertOne(jane)
        ShopServer.shipments.table().insertOne(Shipment(customer = jane._id, trackingNumber = "1Z999"))
    }

    val sent = (ShopServer.email() as TestEmailService).lastEmailTo("jane@example.com")
    assertEquals("Your order shipped", sent?.subject)
    val pushed = (ShopServer.push() as TestNotificationService).lastMessageSent
    assertEquals(listOf("device-1"), pushed?.targets)

    val stored = execute { ShopDispatcher.info.table().all().toList() }.single()
    assertEquals(true, stored.inApp?.sent)
    assertNull(stored.sms)
}
```

Tasks run inline in tests, so everything due immediately is sent before `execute` returns.  A channel switched off
for an event is `null` on the stored `Notification`; one that was sent has `sent = true`.

## Frequencies

Each channel gets a `Frequency`, or `null` to switch it off.

```kotlin
Frequency.immediately()
Frequency.delayed(30.minutes)
Frequency.batch(15)                                   // at the next 15-minute boundary (UTC clock)
Frequency.daily(hour = 9, minute = 0)                 // the server's default time zone
Frequency.daily(9, 0, TimeZone.of("America/Denver"))
Frequency.weekly(DayOfWeek.MONDAY, 9, 0, TimeZone.of("America/Denver"))
Frequency.daily(9, 0).delayed(30.minutes)             // 9:30
```

`TimeZone` and `DayOfWeek` are kotlinx-datetime's.  Notifications for one user on one channel that land on the same
send time are handed to the dispatcher together, so a daily email frequency produces a digest.

The dispatcher's `autoRefreshNotifications` scheduled task sends whatever has come due.  It runs every minute
(`refreshSchedule = Schedule.Frequency(1.minutes)` on the dispatcher) and takes a cache lock so only one server
instance sends.  `ShopDispatcher.refreshNotifications()` runs it on demand.

## Choosing a subscription model

| Provider | Who decides who is notified | User control | Storage |
|---|---|---|---|
| `NonCustomizableSubscriptions` | Your code | None | None |
| `FrequencyCustomizableSubscriptions` | Your code | Frequency per channel, per event | `NotificationSendMethods` table |
| `FullyCustomizableSubscriptions` | Each user's stored subscription | Frequency, channels, and a filter condition | `NotificationEventSubscription` table |

Every event needs content and a subscription registration for the provider in use; a missing one is reported by a
build check when the server definition is built, naming the events.  Each event can be registered once per provider.

### Fixed subscriptions

`subscribed` gives every returned user the same frequencies (defaults come from the `NonCustomizableSubscriptions`
constructor, all `Frequency.immediately()`):

```kotlin
event.subscribed(email = Frequency.daily(9, 0), sms = null) { occurrence -> setOf(occurrence.subject.customer) }
```

`subscribedDirect` sets frequencies per user:

```kotlin
event.subscribedDirect { occurrence ->
    listOf(
        ScheduledSendMethods(user = occurrence.subject.customer, sms = null),
        ScheduledSendMethods(user = warehouseManagerId, email = Frequency.daily(9, 0), sms = null, push = null),
    )
}
```

### User-chosen frequencies

Swap the provider and give it a table for preferences.  Users override the defaults per event through its endpoints;
your code still picks who is interested:

```kotlin
val subscriptions = FrequencyCustomizableSubscriptions<Customer, Uuid>(
    info = ShopServer.database.modelInfo(
        auth = CustomerAuth.require(),
        tableName = "NotificationSendMethods",
        permissions = {
            val own = Condition<NotificationSendMethods<Uuid>> { it._id.user eq auth.id }
            ModelPermissions(create = own, read = own, update = own, delete = own)
        },
    ),
)

val shipped = handler.event("Shipment Sent", ShopServer.shipments) { event ->
    event.subscribed(defaultSms = null) { occurrence -> setOf(occurrence.subject.customer) }
    event.content { occurrence -> { _ -> Alert("Your order shipped", occurrence.subject.trackingNumber) } }
}
```

Mounted under the notifications path at `subscriptions/`: `GET` lists the user's settings for every event (stored or
default, with `isDefault`), `GET {event}` gets one, `POST query` filters them, and `rest/` is the full REST API plus
updates socket for `NotificationSendMethods`.

### User-chosen subscriptions

`FullyCustomizableSubscriptions` stores one subscription per user and event, with a filter on the subject.  Give each
event a default subscription, which new users receive automatically:

```kotlin
val subscriptions = FullyCustomizableSubscriptions<Customer, Uuid>(
    info = ShopServer.database.modelInfo(
        auth = CustomerAuth.require(),
        tableName = "NotificationEventSubscription",
        permissions = {
            val own = Condition<NotificationEventSubscription<Uuid>> { it._id.user eq auth.id }
            ModelPermissions(create = own, read = own, update = own, delete = own)
        },
    ),
    users = ShopServer.customers,
    principal = CustomerAuth,
)

val shipped = handler.event("Shipment Sent", ShopServer.shipments) { event ->
    event.defaultSubscription { customer ->
        FullEventSubscription(
            filter = Condition { it.customer eq customer._id },
            email = Frequency.immediately(),
            push = Frequency.immediately(),
            sms = null,
        )
    }
    event.content { occurrence -> { _ -> Alert("Your order shipped", occurrence.subject.trackingNumber) } }
}
```

A user is notified when their filter matches the subject *and* they can read it under the event model's
permissions; the read permission is computed per user and stored with the subscription.  When a user changes,
`defaultSubscription(behavior = ...)` controls what happens to their existing subscription:

| `DefaultSubscriptionUpdateBehavior` | On user change |
|---|---|
| `UpdateReadPermissions` (default) | Recompute the stored read permission only. |
| `ReplaceExistingWithDefault` | Replace the subscription with the new default; user edits are lost. |
| `UpdateRetainingUserChanges` | Apply the new default only to fields the user hadn't changed. |

Deleting a user deletes their subscriptions.  The updater bulk-deletes and re-inserts rows, so keep that in mind when
adding signals to the subscription table.

## Endpoints

Under the path the `NotificationEndpoints` is mounted at:

| Path | What |
|---|---|
| `rest/` | REST API and updates WebSocket for the stored notifications (the in-app feed), under `notificationInfo`'s permissions. |
| `subscriptions/` | The customizable providers' endpoints, above. |
| `events` | `POST` a `Query<EventType>` to list registered events; needs an `events` scope. |

The generated SDK names the dispatcher's module `NotificationsApi` and the subscriptions module `SubscriptionsApi`.

## Sending push directly

The push service is `NotificationService` from Service Abstractions.  Outside the dispatcher:

```kotlin
val results: Map<String, NotificationSendResult> = ShopServer.push().send(
    targets = customer.pushTokens.toList(),
    data = NotificationData(
        notification = PushNotification(title = "Order shipped", body = "On its way.", link = "myapp://orders/42"),
        data = mapOf("orderId" to "42"),
        android = NotificationAndroid(channel = "orders", priority = NotificationPriority.HIGH),
        ios = NotificationIos(sound = "default"),
        timeToLive = 24.hours,
    ),
)
```

Each token maps to `Success`, `Failure`, or `DeadToken`; remove dead tokens from the user.  The dispatcher does this
for you through `onFcmTokensDead`.

| URL | Module (`sa.` catalog alias) | Register by referencing |
|---|---|---|
| `console`, `test` | `notifications` | (built in) |
| `fcm:///path/to/service-account.json` or `fcm://{...inline JSON...}` | `notifications-fcm` | `FcmNotificationClient` |

`NotificationService.Settings` has no default URL, so the setting must supply one.  Reference
`FcmNotificationClient` (package `com.lightningkite.services.notifications.fcm`) in an `init` block so `fcm://` is
registered before settings load.  The JSON is a Firebase service-account key (Project settings, Service accounts,
Generate new private key); keep it out of the repository.

## Gotchas

- **Event names are unique per server** and become task paths; renaming an event orphans stored subscriptions and
  queued tasks under the old name.
- **Errors while handling an event are logged, not thrown**, so a failing subscription or content function produces
  no notifications and only a log line.
- **A failed send is not retried.**  The refresh task looks only at notifications that came due since its last run.
- **One `NotificationEndpoints` per server.**  Registration checks cover every event in the server, so a second
  handler with its own provider fails them.

## Next

- [Email and SMS](email.md): the services the dispatcher sends through
- [Tasks](tasks.md) and [Schedules](schedules.md): how events and the refresh run
- [Database](database.md): `modelInfo`, signals, and conditions
- [Permissions](permissions.md): what `notificationInfo` exposes to each user
