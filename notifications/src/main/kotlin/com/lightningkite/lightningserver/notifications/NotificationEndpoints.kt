package com.lightningkite.lightningserver.notifications

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.RequiredScope
import com.lightningkite.lightningserver.auth.accepts
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.builder.MapRegistry
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.notifications.NotificationEndpoints.ContentRegistry
import com.lightningkite.lightningserver.notifications.events.Event
import com.lightningkite.lightningserver.notifications.events.EventDefinition
import com.lightningkite.lightningserver.notifications.events.EventEndpoints
import com.lightningkite.lightningserver.notifications.events.EventHandler
import com.lightningkite.lightningserver.notifications.events.EventLauncher
import com.lightningkite.lightningserver.notifications.events.EventRegistry
import com.lightningkite.lightningserver.notifications.events.EventType
import com.lightningkite.lightningserver.notifications.subscriptions.FrequencyCustomizableSubscriptions
import com.lightningkite.lightningserver.notifications.subscriptions.FullyCustomizableSubscriptions
import com.lightningkite.lightningserver.notifications.subscriptions.NonCustomizableSubscriptions
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.lightningserver.typed.auth
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.withSdkInfo
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.ModelPermissions
import com.lightningkite.services.database.condition
import com.lightningkite.services.database.getMany
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Central coordinator for the notification system, connecting events to subscriptions and dispatchers.
 *
 * `NotificationEndpoints` orchestrates the flow of notifications by:
 * 1. Receiving events through the [EventHandler] interface
 * 2. Querying the [subscriptions] provider to determine which users should be notified
 * 3. Generating notification content using registered content generators
 * 4. Passing notifications to the [dispatcher] for delivery
 *
 * ## Subscription Providers
 *
 * Subscription providers determine **who** receives notifications for each event and **when**.
 * They implement the [Subscriptions] interface and return delivery frequencies per channel
 * (email, SMS, push, in-app) for each interested user.
 *
 * **Pre-defined subscription providers:**
 *
 * | Provider | User Customization | Storage | Use Case |
 * |----------|-------------------|---------|----------|
 * | [NonCustomizableSubscriptions] | None | None | Subscriptions defined entirely in code |
 * | [FrequencyCustomizableSubscriptions] | Delivery frequency + Channels | Database | Users choose when (immediate, daily, weekly) and what channels (sms, email, push) |
 * | [FullyCustomizableSubscriptions] | Frequency + Channels + filter conditions | Database | Users choose when, channels, and what (e.g., "orders > $100") |
 *
 * ## Dispatchers
 *
 * Dispatchers handle the **actual delivery** of notifications through various channels.
 * They implement the [Dispatcher] interface and are responsible for storing notifications,
 * formatting content for each channel, and sending via email/SMS/push services.
 *
 * **Pre-defined dispatchers:**
 *
 * | Dispatcher | Features |
 * |------------|----------|
 * | [NotificationBulkDispatcher] | Full-featured dispatcher with batching, scheduling, REST API, and WebSocket support |
 *
 * ## Usage Example
 * ```kotlin
 * object Notifications : NotificationEndpoints<User, Uuid, MyContent, MyDispatcher, MySubscriptions>(
 *     users = Server.userInfo,
 *     dispatcher = MyDispatcher(...),
 *     subscriptions = MySubscriptions(...)
 * ) {
 *     val orderShipped = event("order-shipped", Server.orderInfo) {
 *         it.content { event -> { user -> MyContent("Order shipped!", "Your order is on its way") } }
 *         it.subscribed { event -> setOf(event.subject.customerId) }
 *     }
 * }
 * ```
 *
 * ## Included Endpoints
 * - Dispatcher endpoints (if dispatcher is a ServerBuilder)
 * - Subscription management endpoints at `/subscriptions`
 * - Event type query endpoints at `/events`
 * - Startup verification task at `/verify`
 *
 * @param USER The user type that can receive notifications
 * @param UID The user ID type
 * @param CONTENT The notification content type (application-specific, e.g., title/body pair)
 * @param DISPATCH The dispatcher implementation type
 * @param SUBS The subscriptions provider implementation type
 *
 * @property users Model information for fetching user data
 * @property dispatcher Handles actual delivery of notifications
 * @property subscriptions Determines who receives which notifications
 * @property registry Registry of all event types handled by this notification system
 *
 * @see NonCustomizableSubscriptions
 * @see FrequencyCustomizableSubscriptions
 * @see FullyCustomizableSubscriptions
 * @see NotificationBulkDispatcher
 */
public open class NotificationEndpoints<
    USER : HasId<UID>, UID : Comparable<UID>,
    CONTENT,
    DISPATCH : NotificationEndpoints.Dispatcher<UID, CONTENT>,
    SUBS : NotificationEndpoints.Subscriptions<USER, UID>,
>(
    public val users: ModelInfo<*, USER, UID>,
    public val dispatcher: DISPATCH,
    public val subscriptions: SUBS,
    override val registry: EventRegistry = EventRegistry()
) : EventHandler, ServerBuilder() {
    internal val logger: KLogger = KotlinLogging.logger("com.lightningkite.lightningserver.notifications.NotificationEndpoints")

    /**
     * Registry for content generators that transform events into notification content.
     * Use the [content] extension function on [EventDefinition] to register generators.
     */
    public val content: ContentRegistry<USER, UID, CONTENT> = ContentRegistry()

    init {
        (dispatcher as? ServerBuilder)?.let { path.include(it) }
        (subscriptions as? ServerBuilder)?.let { path.path("subscriptions").module(it.withSdkInfo("SubscriptionsApi", "subscriptions")) }
    }

    context(runtime: ServerRuntime)
    override suspend fun <T : HasId<ID>, ID : Comparable<ID>> handle(event: Event<T, ID>) {
        try {
            logger.debug { "Event Occurred: $event" }

            val content = content.getContent(event.type)(event)

            val now = now()

            val subscribed = subscriptions.subscribed(event)

            if (subscribed.isEmpty()) {
                logger.debug { "No subscriptions found for ${event.type.name}" }
                return
            } else {
                logger.debug { "${subscribed.size} subscriptions found for ${event.type.name}" }
            }

            val users = users.table()
                .getMany(subscribed.map { it.user }.toSet())
                .associateBy { it._id }

            val notifications = subscribed.mapNotNull { sub ->
                val user = users[sub.user] ?: return@mapNotNull null

                Notification(
                    eventData = event.toInternalEventData(),
                    createdAt = now(),
                    user = sub.user,
                    content = content(user),
                    email = sub.email?.schedule(now)?.let(::SendInfo),
                    sms = sub.sms?.schedule(now)?.let(::SendInfo),
                    push = sub.push?.schedule(now)?.let(::SendInfo),
                    inApp = sub.inApp?.schedule(now)?.let(::SendInfo)
                )
            }

            dispatcher.dispatch(notifications)
        } catch (e: Exception) {
            logger.error(e) { "Exception occurred when handling event $event" }
        }
    }

    /**
     * Startup task that verifies all event types have registered content generators,
     * dispatcher configurations, and subscription handlers.
     *
     * This task runs on server startup and will throw an exception if any event type
     * is missing a required dependency, helping catch configuration errors early.
     */
    public val verifyDependencies: StartupTask = path.path("verify") bind StartupTask {
        content.verifyAllDependencies(registry)
        dispatcher.verifyAllDependencies(registry)
        subscriptions.verifyAllDependencies(registry)
    }

    /**
     * REST endpoints for querying registered event types.
     *
     * Mounted at `/events`. Requires the "events" scope for authentication.
     * All authenticated users can read event types; only super users can manage them.
     */
    public open val eventEndpoints: EventEndpoints<HasId<*>> =
        path.path("events") module EventEndpoints(
            registry = registry,
            auth = AuthRequirement.Authenticated(scopes = setOf(RequiredScope("events"))),
            permissions = {
                ModelPermissions(
                    read = Condition.Always,
                    manage = condition(AuthRequirement.IsSuperUser.accepts(auth))
                )
            }
        )

    /**
     * Interface for components that depend on event definitions being registered.
     *
     * Implementors can verify at startup that all required event types have been configured
     * with the necessary handlers, content generators, or subscription logic.
     */
    public interface DefinitionDependency {
        /**
         * Verifies that all event types in the registry have the required configuration.
         *
         * @param registry The registry of all event types to verify against
         * @throws IllegalArgumentException if any required configuration is missing
         */
        context(runtime: ServerRuntime)
        public suspend fun verifyAllDependencies(registry: EventRegistry) {}
    }

    /**
     * Determines which users should receive notifications for a given event.
     *
     * Implementations query or calculate which users are interested in an event
     * and return their preferred delivery frequencies for each channel.
     *
     * ## Pre-defined Implementations
     * - [NonCustomizableSubscriptions]: All subscription logic in code, no user customization
     * - [FrequencyCustomizableSubscriptions]: Users can customize delivery timing only
     * - [FullyCustomizableSubscriptions]: Users can customize both timing and filter conditions
     *
     * @param USER The user type
     * @param UID The user ID type
     *
     * @see NonCustomizableSubscriptions
     * @see FrequencyCustomizableSubscriptions
     * @see FullyCustomizableSubscriptions
     */
    public interface Subscriptions<USER : HasId<UID>, UID : Comparable<UID>> : DefinitionDependency {
        /**
         * Returns the list of users who should be notified about the given event.
         *
         * @param event The event occurrence to find subscribers for
         * @return List of user IDs with their preferred delivery frequencies per channel
         */
        context(runtime: ServerRuntime)
        public suspend fun <T : HasId<ID>, ID : Comparable<ID>> subscribed(event: Event<T, ID>): List<ScheduledSendMethods<UID>>
    }

    /**
     * Handles the actual delivery of notifications through various channels.
     *
     * Dispatchers are responsible for:
     * - Storing notifications in the database
     * - Sending email, SMS, push, and in-app notifications
     * - Managing delivery timing and batching
     *
     * ## Pre-defined Implementations
     * - [NotificationBulkDispatcher]: Full-featured dispatcher with scheduled delivery,
     *   notification batching, REST API for notification management, and WebSocket support
     *   for real-time updates
     *
     * @param UID The user ID type
     * @param CONTENT The notification content type
     *
     * @see NotificationBulkDispatcher
     */
    public interface Dispatcher<UID : Comparable<UID>, CONTENT> : DefinitionDependency {
        /**
         * Dispatches a batch of notifications for delivery.
         *
         * @param notifications The notifications to dispatch
         */
        context(runtime: ServerRuntime)
        public suspend fun dispatch(notifications: List<Notification<UID, CONTENT>>)
    }

    /**
     * Registry for content generators that transform events into notification content.
     *
     * Each event type must have a registered content generator that creates user-specific
     * notification content. The generator receives the event and returns a function that
     * produces content for each individual user (allowing personalized notifications).
     *
     * @param USER The user type
     * @param UID The user ID type
     * @param CONTENT The notification content type (application-specific)
     */
    public class ContentRegistry<USER : HasId<UID>, UID : Comparable<UID>, CONTENT> : ServerBuilder(), DefinitionDependency {
        private val contentGenerators = MapRegistry<String, suspend context(ServerRuntime) (Event<*, *>) -> (USER) -> CONTENT>()

        /**
         * Retrieves the content generator for an event and produces user-specific content.
         *
         * @param event The event to generate content for
         * @return A function that produces content for a specific user
         * @throws NoSuchElementException if no content generator is registered for this event type
         */
        @Suppress("UNCHECKED_CAST")
        context(_: ServerRuntime)
        public fun <T : HasId<ID>, ID : Comparable<ID>> getContent(event: EventDefinition<T, ID>): suspend context(ServerRuntime) (Event<T, ID>) -> (USER) -> CONTENT =
            contentGenerators[event.name]
                ?.let { (it as suspend context(ServerRuntime) (Event<T, ID>) -> (USER) -> CONTENT) }
                ?: throw NoSuchElementException("Event ${event.name} has no content generator")

        /**
         * Registers a content generator for an event type.
         *
         * The generator receives an event and returns a function that produces content
         * for each user. This two-step approach allows the generator to fetch shared data
         * once, then customize content per user.
         *
         * @param event The event type to register the generator for
         * @param generator Function that takes an event and returns a user-to-content function
         */
        public fun <T : HasId<ID>, ID : Comparable<ID>> setContent(
            event: EventDefinition<T, ID>,
            generator: suspend context(ServerRuntime) (Event<T, ID>) -> (USER) -> CONTENT
        ) {
            @Suppress("UNCHECKED_CAST")
            contentGenerators.register(event.name, generator as suspend context(ServerRuntime) (Event<*, *>) -> (USER) -> CONTENT)
        }

        context(runtime: ServerRuntime)
        override suspend fun verifyAllDependencies(registry: EventRegistry) {
            require(contentGenerators.keys.containsAll(registry.keys)) {
                val missing = registry.keys - contentGenerators.keys
                "Content is missing for (${missing.size}) event definitions: $missing"
            }
        }
    }
}

/**
 * DSL function to register a content generator for this event type.
 *
 * The generator receives the event and returns a function that produces content
 * for each user. This two-step approach allows for efficient content generation
 * when multiple users need to be notified about the same event.
 *
 * ## Example
 * ```kotlin
 * orderShipped.content { event ->
 *     val order = event.subject
 *     { user -> NotificationContent(
 *         title = "Order Shipped",
 *         body = "Hi ${user.name}, your order #${order.number} is on its way!"
 *     ) }
 * }
 * ```
 *
 * @param generator Function that takes an event and returns a user-to-content function
 */
context(handler: NotificationEndpoints<USER, UID, CONTENT, *, *>)
public fun <USER : HasId<UID>, UID : Comparable<UID>, CONTENT, T : HasId<ID>, ID : Comparable<ID>> EventDefinition<T, ID>.content(
    generator: suspend context(ServerRuntime) (Event<T, ID>) -> (USER) -> CONTENT
) {
    handler.content.setContent(this, generator)
}

context(_: ServerRuntime)
public inline val <USER, UID, T, ID, CONTENT, H> EventLauncher<H, T, ID>.content: suspend context(ServerRuntime) (Event<T, ID>) -> (USER) -> CONTENT
where USER : HasId<UID>, UID : Comparable<UID>,
      T : HasId<ID>, ID : Comparable<ID>,
      H : NotificationEndpoints<USER, UID, CONTENT, *, *>
    get() = handler.content.getContent(this.event)