# Notifications

The notifications module provides a complete event-driven notification system for Lightning Server applications. It supports multiple delivery channels (email, SMS, push, in-app) with flexible scheduling and user customization options.

## Overview

The notification system consists of three main components:

1. **Events**: Things that happen in your application that might trigger notifications
2. **Subscriptions**: Rules that determine which users get notified about which events
3. **Dispatcher**: Handles queuing, bulking, formatting, and sending notifications

## Quick Start

### 1. Define Your Notification System

```kotlin
object NotificationHandler : NotificationEventHandler<User, Uuid, NotificationContent>(
    users = userInfo,
    dispatcher = notificationDispatcher,
    subscriptions = NonCustomizableSubscriptions()
)
```

### 2. Define Event Types

```kotlin
val postCreated = NotificationHandler.event(
    name = "post-created",
    info = postInfo
) {
    // Configure content for this event type
    it.content { post ->
        { user ->
            NotificationContent(
                title = "New Post",
                body = "${post.author} created a new post: ${post.title}"
            )
        }
    }
}
```

### 3. Subscribe Users to Events

```kotlin
// Subscribe all users to new post notifications
NotificationHandler.subscriptions.addEventListener(
    type = postCreated.type,
    email = Frequency.daily(hour = 9, minute = 0),  // Daily digest at 9 AM
    push = Frequency.immediately(),                  // Immediate push notifications
    interested = { event ->
        // Return set of user IDs who should be notified
        userTable.find(Condition.Always).toList().map { it._id }.toSet()
    }
)
```

### 4. Trigger Events

```kotlin
// When a post is created
postCreated(newPost)
```

## Subscription Models

The notifications module provides three subscription strategies:

### NonCustomizableSubscriptions

All subscription logic is defined in code. Users cannot customize their notification preferences.

**Use when:**
- Notifications are mandatory (e.g., security alerts)
- You want complete control over notification delivery
- You don't want to manage subscription state in the database

```kotlin
val subscriptions = NonCustomizableSubscriptions<User, Uuid>()

subscriptions.addEventListener(
    type = securityAlert,
    email = Frequency.immediately(),
    sms = Frequency.immediately(),
    push = Frequency.immediately(),
    interested = { event ->
        setOf(event.subject.userId) // Only notify the affected user
    }
)
```

### FrequencyCustomizableSubscriptions

Users can customize delivery frequencies but not which events they receive. The logic for determining interested users is defined in code.

**Use when:**
- Event logic is complex or security-sensitive
- You want users to control *how* but not *what* they're notified about
- You want programmatic control over subscription logic

```kotlin
val subscriptions = FrequencyCustomizableSubscriptions<User, Uuid>(
    info = notificationSendMethodsInfo
)

subscriptions.addEventListener(
    type = commentOnMyPost,
    defaultEmail = Frequency.batch(60),  // Hourly by default
    defaultPush = Frequency.immediately(),
    interested = { event ->
        // Find users who should be notified based on the event
        setOf(event.subject.postAuthorId)
    }
)
```

Users can then customize their preferences via REST endpoints:

```kotlin
val rest = notificationDispatcher.rest  // Auto-generated REST endpoints
```

### FullyCustomizableSubscriptions

Users have complete control over both filtering conditions and delivery frequencies.

**Use when:**
- Users need maximum flexibility
- You trust users to create their own subscription filters
- Your application has complex filtering requirements

```kotlin
val subscriptions = FullyCustomizableSubscriptions<User, Uuid>(
    info = notificationEventSubscriptionInfo,
    users = userInfo,
    principal = userPrincipal,
    events = NotificationHandler.registry
)

// Define default subscriptions for new users
subscriptions.setDefaultSubscription(
    type = postCreated,
    subscription = { user ->
        Subscription(
            filter = condition { it.author eq "favorite-author" },
            email = Frequency.daily(hour = 9, minute = 0),
            push = Frequency.immediately(),
            sms = null  // Disable SMS
        )
    }
)
```

**Important:** FullyCustomizableSubscriptions automatically manages default subscriptions:
- New users get all default subscriptions
- When defaults change, existing subscriptions are updated based on `DefaultSubscriptionUpdateBehavior`
- Read permissions are automatically enforced (users only see events they have permission to view)

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
Frequency.weekly(weekDay = DayOfWeek.MONDAY, hour = 9, minute = 0)

// Combined: daily with delay
Frequency.daily(hour = 9, minute = 0).delayed(30.minutes)

// Disable a channel
email = null  // No email notifications
```

## Notification Dispatcher

The dispatcher handles queuing, formatting, and sending notifications. You must implement:

```kotlin
abstract class NotificationBulkDispatcher<USER, UID, CONTENT>(
    // ... configuration ...
) {
    // Contact information
    context(ServerRuntime)
    abstract suspend fun email(user: USER): EmailAddress?

    context(ServerRuntime)
    abstract suspend fun phone(user: USER): PhoneNumber?

    context(ServerRuntime)
    abstract suspend fun fcmTokens(user: USER): Set<String>

    // Formatting (with bulking support)
    context(ServerRuntime)
    abstract suspend fun makeEmailNotifications(
        user: USER,
        notifications: List<Notification<UID, CONTENT>>
    ): List<Email>

    context(ServerRuntime)
    abstract suspend fun makeSmsNotifications(
        user: USER,
        notifications: List<Notification<UID, CONTENT>>
    ): List<String>

    context(ServerRuntime)
    abstract suspend fun makePushNotifications(
        user: USER,
        notifications: List<Notification<UID, CONTENT>>
    ): List<NotificationData>
}
```

### Notification Bulking

When multiple notifications for the same user are scheduled at the same time, they're grouped together. You control how they're formatted:

```kotlin
override suspend fun makeEmailNotifications(
    user: USER,
    notifications: List<Notification<UID, CONTENT>>
): List<Email> {
    // Option 1: Send one email with all notifications
    return listOf(
        Email(
            to = email(user)!!,
            subject = "${notifications.size} new notifications",
            body = notifications.joinToString("\n") { it.content.body }
        )
    )

    // Option 2: Send separate emails for important ones
    return notifications.map { notif ->
        Email(
            to = email(user)!!,
            subject = notif.content.title,
            body = notif.content.body
        )
    }
}
```

## Scheduling

The dispatcher includes an automatic scheduler that checks for pending notifications every minute:

```kotlin
// Automatically included
val autoRefreshNotifications: ScheduledTask

// Manual refresh if needed
context(ServerRuntime)
suspend fun refreshNotifications()
```

## REST Endpoints

For customizable subscription models, REST endpoints are automatically generated:

```kotlin
// FullyCustomizableSubscriptions
val rest: ModelRestEndpoints<NotificationEventSubscription<UID>>

// FrequencyCustomizableSubscriptions
val rest: ModelRestEndpoints<NotificationSendMethods<UID>>

// NotificationBulkDispatcher
val rest: ModelRestEndpointsAndUpdatesWebsocket<Notification<UID, CONTENT>>
```

These provide standard CRUD operations for managing subscriptions and querying notifications.

## Event Tags

Organize events with tags for easier management:

```kotlin
val postCreated = NotificationHandler.event(
    name = "post-created",
    info = postInfo,
    tags = setOf("posts", "social", "user-generated-content")
)

// Query event types by tags
eventEndpoints.queryEventTypes(
    Query(condition { it.tags contains "posts" })
)
```

## Best Practices

### 1. Content Generation

Keep content generation logic simple and fast. It runs for every interested user:

```kotlin
it.content { post ->
    { user ->
        // Fast: direct field access
        NotificationContent(
            title = "New Post",
            body = post.title
        )

        // Avoid: additional database queries per user
        // val author = authorTable.get(post.authorId)  // DON'T DO THIS
    }
}
```

### 2. Subscription Logic

For `NonCustomizableSubscriptions` and `FrequencyCustomizableSubscriptions`, optimize the `interested` function:

```kotlin
// Good: single query
interested = { event ->
    followTable
        .find(condition { it.following eq event.subject.authorId })
        .toList()
        .map { it.follower }
        .toSet()
}

// Avoid: querying per user
interested = { event ->
    users.filter { user ->
        // followTable.find(...) per user
    }
}
```

### 3. Default Frequencies

Choose sensible defaults based on notification importance:

```kotlin
// High importance: immediate
Frequency.immediately()

// Medium importance: batched
Frequency.batch(60)  // Hourly

// Low importance: daily digest
Frequency.daily(hour = 9, minute = 0)
```

### 4. Testing

Use the inline handler for testing:

```kotlin
@Test
fun testPostCreatedNotification() = runBlocking {
    val engine = LocalEngine(Server.build())

    // Trigger event inline (doesn't use task system)
    postCreated.handleInline(testPost)

    // Verify notifications were created
    val notifications = notificationTable.find(Condition.Always).toList()
    assertEquals(1, notifications.size)
}
```

## Common Patterns

### Role-Based Notifications

```kotlin
subscriptions.addEventListener(
    type = adminAlert,
    interested = { event ->
        userTable
            .find(condition { it.role eq UserRole.ADMIN })
            .toList()
            .map { it._id }
            .toSet()
    }
)
```

### Relationship-Based Notifications

```kotlin
subscriptions.addEventListener(
    type = commentOnMyPost,
    interested = { event ->
        // Notify post author
        setOf(event.subject.post.authorId)
    }
)
```

### Proximity-Based Notifications

```kotlin
subscriptions.addEventListener(
    type = nearbyEvent,
    interested = { event ->
        userLocationTable
            .find(condition {
                it.location.within(event.subject.location, radius = 10.miles)
            })
            .toList()
            .map { it.userId }
            .toSet()
    }
)
```

## Troubleshooting

### Notifications Not Sending

1. Check that `autoRefreshNotifications` is enabled
2. Verify the scheduler has cache access for locking
3. Check logs for errors in the dispatcher
4. Ensure contact information (email, phone, FCM tokens) is available

### Users Not Receiving Notifications

1. Verify subscription logic returns the user ID
2. Check that content generator runs without errors
3. Verify read permissions (for FullyCustomizableSubscriptions)
4. Check that the user hasn't disabled the notification channel

### Performance Issues

1. Optimize `interested` functions to use single queries
2. Consider batching for high-volume events
3. Monitor notification content generation time
4. Use appropriate database indexes on notification table (`user`, `sendAt`)

## See Also

- [Events](./notifications-events.md) - Detailed event system documentation
- [Database](./database.md) - Query DSL for subscription filtering
- [Authentication](./authentication.md) - Permission integration
