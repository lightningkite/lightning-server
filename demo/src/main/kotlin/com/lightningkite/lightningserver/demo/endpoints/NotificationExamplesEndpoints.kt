package com.lightningkite.lightningserver.demo.endpoints

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import kotlinx.serialization.Serializable

/**
 * NotificationExamplesEndpoints - Demonstrates the Lightning Server notification system.
 *
 * The notifications module provides a powerful, event-driven notification system with:
 * - Multiple delivery channels (email, SMS, push, in-app)
 * - Flexible scheduling (immediate, delayed, batch, daily, weekly)
 * - Automatic bulking of notifications
 * - User-customizable notification preferences
 * - Type-safe event system
 *
 * Note: This is a simplified demonstration. In a real application, you would:
 * 1. Set up NotificationBulkDispatcher with your user model
 * 2. Define event types with NotificationEventHandler
 * 3. Configure subscription providers (NonCustomizable, FrequencyCustomizable, or FullyCustomizable)
 * 4. Integrate with email, SMS, and push notification services
 *
 * The full notification system is production-ready and handles:
 * - Queuing and scheduling
 * - Retry logic
 * - Dead token cleanup (for push notifications)
 * - Permission-aware filtering
 * - Real-time WebSocket updates
 */
object NotificationExamplesEndpoints : ServerBuilder() {

    /**
     * POST /notifications/concepts
     *
     * Explains the core concepts of the Lightning Server notification system.
     * This is an educational endpoint that documents the architecture.
     */
    val concepts = path.path("notifications").path("concepts").post bind ApiHttpHandler(
        summary = "Notification system concepts",
        description = "Provides an overview of the Lightning Server notification architecture",
        auth = noAuth,
        successCode = HttpStatus.OK,
        implementation = { _: Unit ->
            NotificationConceptsResponse(
                architecture = """
                    Event occurs → EventHandler → SubscriptionProvider (determines audience) →
                    ContentGenerator → NotificationDispatcher → Database →
                    Scheduled task (every minute) → Format & Send (email/SMS/push/in-app)
                """.trimIndent(),

                deliveryChannels = listOf(
                    "Email - Rich HTML formatting with bulking support",
                    "SMS - Text messages with character limits",
                    "Push - Mobile push notifications via FCM",
                    "In-App - Stored in database for UI display"
                ),

                schedulingOptions = listOf(
                    "Immediately - Send as soon as possible",
                    "Delayed - Send after a specified duration",
                    "Batch - Group notifications sent every N minutes",
                    "Daily - Send once per day at a specific time",
                    "Weekly - Send once per week on a specific day"
                ),

                subscriptionModels = mapOf(
                    "NonCustomizable" to "All logic is programmatic - simplest to set up",
                    "FrequencyCustomizable" to "Users can customize delivery frequencies",
                    "FullyCustomizable" to "Users can define custom filters and frequencies"
                )
            )
        }
    )

    /**
     * POST /notifications/example-event
     *
     * Demonstrates how to define and trigger an event in the notification system.
     * Shows the code pattern for creating typed events.
     */
    val exampleEvent = path.path("notifications").path("example-event").post bind ApiHttpHandler(
        summary = "Example event definition",
        description = "Shows how to define a notification event and trigger it",
        auth = noAuth,
        successCode = HttpStatus.OK,
        implementation = { input: ExampleEventRequest ->
            ExampleEventResponse(
                eventName = input.eventType,
                explanation = when (input.eventType) {
                    "post-created" -> """
                        // Define the event type
                        val postCreated = eventHandler.event(
                            name = "post-created",
                            info = postsInfo,
                            tags = setOf("posts", "content")
                        ) {
                            it.content { post ->
                                { user ->
                                    NotificationContent(
                                        title = "New Post: ${'$'}{post.title}",
                                        message = "Check out the latest post from ${'$'}{post.author}",
                                        actionUrl = "/posts/${'$'}{post._id}"
                                    )
                                }
                            }
                        }

                        // Trigger the event
                        postCreated(newPost)
                    """.trimIndent()

                    "comment-received" -> """
                        // Define event with dynamic content
                        val commentReceived = eventHandler.event(
                            name = "comment-received",
                            info = commentsInfo,
                            tags = setOf("comments", "engagement")
                        ) {
                            it.content { comment ->
                                { user ->
                                    NotificationContent(
                                        title = "New Comment",
                                        message = "${'$'}{comment.author} commented on your post",
                                        actionUrl = "/posts/${'$'}{comment.postId}#comment-${'$'}{comment._id}"
                                    )
                                }
                            }
                        }

                        // Set up subscription - notify post authors
                        subscriptions.addEventListener(
                            type = commentReceived.type,
                            email = Frequency.immediately(),
                            push = Frequency.immediately(),
                            interested = { event ->
                                // Find the post author
                                val post = database().table<Post>().get(event.subject.postId)
                                setOf(post?.authorId ?: return@addEventListener emptySet())
                            }
                        )
                    """.trimIndent()

                    else -> """
                        // Generic event pattern
                        val myEvent = eventHandler.event(
                            name = "${input.eventType}",
                            info = modelInfo,
                            tags = setOf("category")
                        ) {
                            it.content { subject ->
                                { user ->
                                    NotificationContent(
                                        title = "Event: ${input.eventType}",
                                        message = "Something happened",
                                        actionUrl = "/items/${'$'}{subject._id}"
                                    )
                                }
                            }
                        }
                    """.trimIndent()
                }
            )
        }
    )

    /**
     * POST /notifications/scheduling-examples
     *
     * Demonstrates different notification scheduling options.
     * Shows how to configure when and how often notifications are sent.
     */
    val schedulingExamples = path.path("notifications").path("scheduling-examples").post bind ApiHttpHandler(
        summary = "Notification scheduling patterns",
        description = "Examples of different scheduling configurations for notifications",
        auth = noAuth,
        successCode = HttpStatus.OK,
        implementation = { input: SchedulingExampleRequest ->
            val examples = mapOf(
                "immediate" to """
                    // Send right away
                    Frequency.immediately()
                """.trimIndent(),

                "delayed" to """
                    // Wait 30 minutes before sending
                    Frequency.delayed(30.minutes)
                """.trimIndent(),

                "batch" to """
                    // Group notifications sent every 15 minutes
                    Frequency.batch(15)
                """.trimIndent(),

                "daily" to """
                    // Send once per day at 9:00 AM
                    Frequency.daily(
                        hour = 9,
                        minute = 0,
                        timeZone = TimeZone.of("America/New_York")
                    )

                    // Or using time string
                    Frequency.daily(
                        time = "09:00",
                        timeZone = TimeZone.currentSystemDefault()
                    )
                """.trimIndent(),

                "weekly" to """
                    // Send every Monday at 9:00 AM
                    Frequency.weekly(
                        weekDay = DayOfWeek.MONDAY,
                        hour = 9,
                        minute = 0,
                        timeZone = TimeZone.of("America/New_York")
                    )
                """.trimIndent(),

                "combined" to """
                    // Daily at 10:00 AM (9:00 + 1 hour delay)
                    Frequency.daily(hour = 9, minute = 0).delayed(1.hours)
                """.trimIndent()
            )

            SchedulingExampleResponse(
                requestedType = input.scheduleType,
                codeExample = examples[input.scheduleType] ?: examples["immediate"]!!,
                description = when (input.scheduleType) {
                    "immediate" -> "Best for urgent notifications that users need to see right away"
                    "delayed" -> "Useful for giving users time to complete an action before notifying"
                    "batch" -> "Groups multiple notifications to avoid overwhelming users"
                    "daily" -> "Perfect for digest emails or daily summaries"
                    "weekly" -> "Great for weekly reports or roundups"
                    "combined" -> "Mix scheduling strategies for complex timing requirements"
                    else -> "Choose the right schedule based on notification urgency and user preferences"
                }
            )
        }
    )

    /**
     * POST /notifications/subscription-patterns
     *
     * Explains the three subscription provider models and when to use each.
     */
    val subscriptionPatterns = path.path("notifications").path("subscription-patterns").post bind ApiHttpHandler(
        summary = "Subscription provider patterns",
        description = "Compares the three subscription models and provides guidance on choosing one",
        auth = noAuth,
        successCode = HttpStatus.OK,
        implementation = { _: Unit ->
            SubscriptionPatternsResponse(
                models = listOf(
                    SubscriptionModel(
                        name = "NonCustomizableSubscriptions",
                        complexity = "Simple",
                        userControl = "None - all logic is programmatic",
                        storageRequired = "No database storage needed",
                        bestFor = "MVPs, internal tools, simple notification needs",
                        example = """
                            val subscriptions = NonCustomizableSubscriptions<User, Uuid>()

                            subscriptions.addEventListener(
                                type = postCreated.type,
                                email = Frequency.daily(hour = 9, minute = 0),
                                push = Frequency.immediately(),
                                interested = { event -> setOf(event.subject.authorId) }
                            )
                        """.trimIndent()
                    ),

                    SubscriptionModel(
                        name = "FrequencyCustomizableSubscriptions",
                        complexity = "Moderate",
                        userControl = "Users can customize delivery frequencies",
                        storageRequired = "Stores NotificationSendMethods per user",
                        bestFor = "Most production applications with user preferences",
                        example = """
                            val subscriptions = path.path("subscriptions") include
                                FrequencyCustomizableSubscriptions<User, Uuid>(subscriptionInfo)

                            subscriptions.addEventListener(
                                type = postCreated.type,
                                defaultEmail = Frequency.daily(hour = 9, minute = 0),
                                defaultPush = Frequency.immediately(),
                                interested = { event -> setOf(event.subject.authorId) }
                            )

                            // Users can override via REST API:
                            // POST /subscriptions/rest/NotificationSendMethods
                            // { "_id": { "user": "...", "type": "..." }, "email": {...} }
                        """.trimIndent()
                    ),

                    SubscriptionModel(
                        name = "FullyCustomizableSubscriptions",
                        complexity = "Advanced",
                        userControl = "Users can define custom filters AND frequencies",
                        storageRequired = "Stores full Subscription objects with filters",
                        bestFor = "Enterprise apps, SaaS platforms, power users",
                        example = """
                            val subscriptions = path.path("subscriptions") include
                                FullyCustomizableSubscriptions<User, Uuid>(
                                    info = subscriptionInfo,
                                    users = users,
                                    principal = userPrincipal,
                                    events = eventRegistry.events
                                )

                            subscriptions.setDefaultSubscription(
                                type = postCreated.type,
                                subscription = { user ->
                                    Subscription(
                                        filter = condition { it.authorId eq user._id },
                                        email = Frequency.daily(hour = 9, minute = 0),
                                        push = Frequency.immediately()
                                    )
                                }
                            )

                            // Users can create custom subscriptions:
                            // "Notify me when posts contain 'Kotlin' in the title"
                        """.trimIndent()
                    )
                ),
                recommendation = """
                    Start with FrequencyCustomizableSubscriptions for most applications.
                    It provides a good balance of flexibility and simplicity.

                    Use NonCustomizableSubscriptions for MVPs or internal tools.
                    Upgrade to FullyCustomizableSubscriptions when users need advanced filtering.
                """.trimIndent()
            )
        }
    )
}

// Request/Response models for typed endpoints

@Serializable
data class NotificationConceptsResponse(
    val architecture: String,
    val deliveryChannels: List<String>,
    val schedulingOptions: List<String>,
    val subscriptionModels: Map<String, String>
)

@Serializable
data class ExampleEventRequest(
    val eventType: String
)

@Serializable
data class ExampleEventResponse(
    val eventName: String,
    val explanation: String
)

@Serializable
data class SchedulingExampleRequest(
    val scheduleType: String
)

@Serializable
data class SchedulingExampleResponse(
    val requestedType: String,
    val codeExample: String,
    val description: String
)

@Serializable
data class SubscriptionPatternsResponse(
    val models: List<SubscriptionModel>,
    val recommendation: String
)

@Serializable
data class SubscriptionModel(
    val name: String,
    val complexity: String,
    val userControl: String,
    val storageRequired: String,
    val bestFor: String,
    val example: String
)
