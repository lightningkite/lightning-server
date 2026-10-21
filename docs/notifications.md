# Notifications

The notifications module provides a complete event-driven notification system for Lightning Server applications. It
supports multiple delivery channels (email, SMS, push, in-app) with flexible scheduling and user customization options.

## Overview

The notification system consists of four parts:

1. **Events**: Things that happen in your application that might trigger notifications, defined as `EventDefinition`s
2. **Subscriptions**: A provider that decides which users get notified about each event, and how often per channel
3. **Content**: A generator per event that produces each user's notification content
4. **Dispatcher**: Stores, bulks, formats, and sends notifications

`NotificationEndpoints` ties them together: it receives each event, asks the subscription provider who is interested,
generates their content, and hands the notifications to the dispatcher.

## Quick Start

### 1. Define a Dispatcher

`NotificationBulkDispatcher` is the built-in dispatcher. Extend it to say how to reach a user and how to format their
notifications:

```kotlin
@Serializable
data class NotificationContent(val title: String, val body: String)

object Dispatcher : NotificationBulkDispatcher<User, Uuid, NotificationContent>(
    info = Server.database.modelInfo(auth = User.require(), tableName = "Notification", permissions = { /* ... */ }),
    cache = Server.cache,
    database = Server.database,
    users = Server.userInfo,
    contentSerializer = NotificationContent.serializer(),
    email = Server.email,
    sms = Server.sms,
) {
    context(server: ServerRuntime)
    override suspend fun email(user: User): EmailAddress? = user.email.toEmailAddress()
    context(server: ServerRuntime)
    override suspend fun phone(user: User): PhoneNumber? = user.phone?.toPhoneNumber()
    context(server: ServerRuntime)
    override suspend fun fcmTokens(user: User): Set<String> = emptySet()
    context(server: ServerRuntime)
    override suspend fun onFcmTokensDead(user: User, deadTokens: Set<String>) {}

    context(runtime: ServerRuntime)
    override suspend fun makeEmailNotifications(user: User, notifications: List<Notification<Uuid, NotificationContent>>) =
        notifications.map { Email(subject = it.content.title, to = listOf(EmailAddressWithName(user.email)), plainText = it.content.body) }

    context(runtime: ServerRuntime)
    override suspend fun makeSmsNotifications(user: User, notifications: List<Notification<Uuid, NotificationContent>>) =
        notifications.map { it.content.body }

    context(runtime: ServerRuntime)
    override suspend fun makePushNotifications(user: User, notifications: List<Notification<Uuid, NotificationContent>>) =
        emptyList<NotificationData>()
}
```

### 2. Create the Notification System

```kotlin
object Notifications : ServerBuilder() {
    val handler = path include NotificationEndpoints(
        users = Server.userInfo,
        dispatcher = Dispatcher,
        subscriptions = FrequencyCustomizableSubscriptions(
            info = Server.database.modelInfo(auth = User.require(), tableName = "NotificationSendMethods", permissions = { /* ... */ })
        ),
    )
}
```

### 3. Define Events

`event` defines an event type and returns an `EventLauncher`. Its setup block says who is subscribed and what they
receive:

```kotlin
object PostEndpoints : ServerBuilder() {
    val info = Server.database.modelInfo(
        auth = User.require(),
        tableName = "Post",
        permissions = { /* ... */ },
        signals = { table -> table.postCreate { postCreated(it) } },  // Launch the event whenever a post is created
    )

    val postCreated = Notifications.handler.event("Post Created", info) { event ->
        event.subscribed { occurrence ->
            followersOf(occurrence.subject.authorId)  // The set of user IDs to notify
        }
        event.content { occurrence ->
            { user -> NotificationContent("New post", "${occurrence.subject.title} was just posted") }
        }
    }
}
```

Every event must have content; a startup task fails the server if one does not.

### 4. Trigger Events

Invoke the launcher with the subject. This queues the event's task, which runs as a child of the current execution:

```kotlin
postCreated(post)
```

`postCreated.handleInline(post)` handles it immediately instead, which is useful in tests.

## Subscription Providers

### NonCustomizableSubscriptions

Subscriptions are defined entirely in code, with no database storage. Use `subscribedDirect` to return each interested
user with their frequencies:

```kotlin
event.subscribedDirect { occurrence ->
    adminIds().map { id ->
        ScheduledSendMethods.Data(user = id, email = Frequency.immediately(), sms = null, push = null, inApp = Frequency.immediately())
    }
}
```

When several definitions target the same user, the earliest scheduled time for each channel wins. Best for mandatory
notifications.

### FrequencyCustomizableSubscriptions

Your code decides who is interested, and users choose how often they are told on each channel. Preferences are stored
in a `NotificationSendMethods` table with REST endpoints. Use `subscribed`, optionally overriding the default
frequencies:

```kotlin
event.subscribed(defaultEmail = Frequency.daily(hour = 9, minute = 0)) { occurrence ->
    setOf(occurrence.subject.ownerId)
}
```

### FullyCustomizableSubscriptions

Users choose what they hear about, with filter conditions, as well as how often. Subscriptions are stored in a
`NotificationEventSubscription` table with REST endpoints. Use `defaultSubscription` to give each user their starting
subscription:

```kotlin
event.defaultSubscription { user ->
    FullEventSubscription(
        filter = Condition.Always,
        email = Frequency.immediately(),
        push = Frequency.immediately(),
        sms = null,  // Disabled
    )
}
```

FullyCustomizableSubscriptions manages default subscriptions automatically:

- New users get all default subscriptions
- When defaults change, existing subscriptions are updated according to `DefaultSubscriptionUpdateBehavior`
  (`UpdateReadPermissions`, `ReplaceExistingWithDefault`, or `UpdateRetainingUserChanges`)
- Read permissions are enforced, so users only hear about events they are allowed to see

## Notification Frequencies

Control when notifications are sent using the `Frequency` class:

```kotlin
// Immediate delivery
Frequency.immediately()

// Delayed delivery
Frequency.delayed(30.minutes)

// Batch at regular intervals
Frequency.batch(15)  // Every 15 minutes

// Daily at specific time
Frequency.daily(hour = 9, minute = 0, timeZone = TimeZone.of("America/New_York"))

// Weekly on specific day
Frequency.weekly(TimeZone.of("America/New_York"), DayOfWeek.MONDAY, LocalTime(9, 0))

// Combined: daily with delay
Frequency.daily(hour = 9, minute = 0).delayed(30.minutes)
```

A `null` frequency disables that channel.

## Notification Bulking

When multiple notifications for the same user are due at the same time on the same channel, they are passed to your
`make…Notifications` function together. You decide how to format them:

```kotlin
context(runtime: ServerRuntime)
override suspend fun makeEmailNotifications(
    user: User,
    notifications: List<Notification<Uuid, NotificationContent>>
): List<Email> = listOf(
    // One email covering every notification
    Email(
        subject = "${notifications.size} new notifications",
        to = listOf(EmailAddressWithName(user.email)),
        plainText = notifications.joinToString("\n") { it.content.body },
    )
)
```

## Scheduling

The dispatcher includes a scheduled task, `autoRefreshNotifications`, that finds and sends pending notifications. It
runs every minute by default; pass `refreshSchedule` to the dispatcher to change that. Sending is locked so that only one
instance processes a notification.

## REST Endpoints

The customizable subscription providers and the dispatcher each expose REST endpoints:

- `FrequencyCustomizableSubscriptions.rest` - the user's send-method preferences
- `FullyCustomizableSubscriptions.rest` - the user's event subscriptions
- `NotificationBulkDispatcher.rest` - stored notifications, with a WebSocket for in-app notifications as they arrive

`NotificationEndpoints` also mounts the registered event types at `/events`.

## Event Tags

Organize events with tags for easier management:

```kotlin
val postCreated = Notifications.handler.event(
    name = "Post Created",
    info = postInfo,
    tags = setOf("posts", "social"),
) { event -> /* ... */ }
```

## Best Practices

1. **Fetch shared data once**: The content generator runs once per event and returns a function run once per user, so
   load anything common to every user in the outer function.
2. **Keep subscriber logic cheap**: It runs for every event occurrence; query by indexed fields.
3. **Choose conservative defaults**: Prefer batched or daily email for frequent events, and let users opt in to more.
4. **Test with test services**: Set the email and SMS settings to `TestEmailService` and `TestSMS` with `setStatic`, then
   inspect what was sent.

## Troubleshooting

### Notifications Not Sending

- Check that the dispatcher's scheduled task is running (it is a `ScheduledTask` on the dispatcher)
- Check the user has an email, phone, or FCM token for the channel
- Check the frequency for that channel is not `null`

### Users Not Receiving Notifications

- Check the subscription provider returns the user for that event
- With FullyCustomizableSubscriptions, check the user's filter matches and they can read the subject

## See Also

- [Tasks](tasks.md) - How event tasks are queued
- [Email](email.md) - Email service configuration
- [Database](database.md) - `modelInfo` and `signals`
