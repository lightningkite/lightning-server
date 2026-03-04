package com.lightningkite.lightningserver.notifications.subscriptions

import com.lightningkite.lightningserver.definition.builder.MapRegistry
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.notifications.Frequency
import com.lightningkite.lightningserver.notifications.NotificationEndpoints
import com.lightningkite.lightningserver.notifications.ScheduledSendMethods
import com.lightningkite.lightningserver.notifications.events.Event
import com.lightningkite.lightningserver.notifications.events.EventDefinition
import com.lightningkite.lightningserver.notifications.events.EventRegistry
import com.lightningkite.lightningserver.notifications.events.UserEventType
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.lightningserver.typed.ModelRestEndpointsAndUpdatesWebsocket
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.SerializableProperty
import com.lightningkite.services.database.getMany
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Subscription provider allowing users to customize delivery frequencies only.
 *
 * This is a middle-ground subscription model where:
 * - The application determines which users are interested in each event (via [setSubscribers])
 * - Users can customize when they receive notifications (immediately, daily digest, weekly, etc.)
 * - Users cannot customize filter conditions
 *
 * This is useful when the business logic for determining interested users is complex and
 * should be controlled by the application, but users should still be able to control
 * notification frequency.
 *
 * ## How It Works
 * 1. Register event listeners with [setSubscribers] that determine interested users and default frequencies
 * 2. Users can override frequencies via the REST API
 * 3. When an event occurs, the system queries listeners for interested users, then applies user preferences
 *
 * ## Endpoints
 * - REST API at `/rest` for managing delivery frequencies
 * - WebSocket for real-time subscription updates
 *
 * @param USER The user type
 * @param UID The user ID type
 * @property info Model information for subscription storage and permissions
 */
public class FrequencyCustomizableSubscriptions<USER : HasId<UID>, UID : Comparable<UID>>(
    public val info: ModelInfo<*, NotificationSendMethods<UID>, UserEventType<UID>>,
    websocketKey: SerializableProperty<NotificationSendMethods<UID>, *>? = info.serializer.fieldInApp
) : ServerBuilder(), NotificationEndpoints.Subscriptions<USER, UID> {
    private val logger: KLogger = KotlinLogging.logger("com.lightningkite.lightningserver.notifications.subscriptions.FrequencyCustomizableSubscriptions")

    private data class Subscriptions<UID : Comparable<UID>, T : HasId<ID>, ID : Comparable<ID>>(
        val eventType: EventDefinition<T, *>,
        val defaultEmail: Frequency? = Frequency.immediately(),
        val defaultSms: Frequency? = Frequency.immediately(),
        val defaultPush: Frequency? = Frequency.immediately(),
        val defaultInApp: Frequency? = Frequency.immediately(),
        val interested: suspend context(ServerRuntime) (Event<T, ID>) -> Set<UID>
    )

    private val eventListeners = MapRegistry<String, Subscriptions<UID, *, *>>()

    /**
     * Registers an event listener that determines interested users and default frequencies.
     *
     * Multiple listeners can be registered for the same event type. When an event occurs,
     * all listeners are invoked and their interested users are merged. If multiple listeners
     * specify different frequencies for the same user and channel, the earliest scheduled
     * time is used.
     *
     * @param type The event type to listen for
     * @param defaultEmail Default email delivery frequency (null to disable)
     * @param defaultSms Default SMS delivery frequency (null to disable)
     * @param defaultPush Default push notification delivery frequency (null to disable)
     * @param defaultInApp Default in-app notification delivery frequency (null to disable)
     * @param interested Function that returns the set of user IDs interested in the event
     */
    public fun <T : HasId<ID>, ID : Comparable<ID>> setSubscribers(
        type: EventDefinition<T, ID>,
        defaultEmail: Frequency? = Frequency.immediately(),
        defaultSms: Frequency? = Frequency.immediately(),
        defaultPush: Frequency? = Frequency.immediately(),
        defaultInApp: Frequency? = Frequency.immediately(),
        interested: suspend context(ServerRuntime) (Event<T, ID>) -> Set<UID>
    ) {
        eventListeners.register(type.name, Subscriptions(type, defaultEmail, defaultSms, defaultPush, defaultInApp, interested))
    }

    @Suppress("UNCHECKED_CAST")
    context(server: ServerRuntime)
    override suspend fun <T : HasId<ID>, ID : Comparable<ID>> subscribed(event: Event<T, ID>): List<ScheduledSendMethods<UID>> = try {
        val listener = eventListeners[event.type.name]?.let { it as Subscriptions<UID, T, ID> } ?: return emptyList()

        val interested = listener.interested(event)

        val userSpecifiedMethods = info
            .table()
            .getMany(interested.map { UserEventType(it, event.type.untyped) })
            .associateBy { it._id.user }

        interested.map { user ->
            userSpecifiedMethods[user]
                ?.let { NotificationSendMethods(_id = it._id, email = it.email, push = it.push, sms = it.sms, inApp = it.inApp) }
                ?: NotificationSendMethods(
                    _id = UserEventType(user, event.type.untyped),
                    email = listener.defaultEmail,
                    push = listener.defaultPush,
                    sms = listener.defaultSms,
                    inApp = listener.defaultInApp,
                )
        }
    } catch (e: ClassCastException) {
        logger.error(e) { "Getting event listeners for notification subscriptions" }
        emptyList()
    }

    context(runtime: ServerRuntime)
    override suspend fun verifyAllDependencies(registry: EventRegistry) {
        require(eventListeners.keys.containsAll(registry.keys)) {
            val missing = registry.keys - eventListeners.keys
            "Subscriptions are missing for (${missing.size}) event definitions: $missing"
        }
    }

    /**
     * REST and WebSocket endpoints for managing delivery frequency preferences.
     * Mounted at `/rest`. Provides CRUD operations and real-time updates for preferences.
     */
    public val rest: ModelRestEndpointsAndUpdatesWebsocket<*, NotificationSendMethods<UID>, UserEventType<UID>> =
        path.path("rest") include ModelRestEndpointsAndUpdatesWebsocket(info, websocketKey)
}

/**
 * DSL function to register a subscriber generator for this event type.
 *
 * The generator function determines which users should be notified when this event occurs.
 * All matched users receive notifications with the specified default frequencies unless
 * they have customized their preferences.
 *
 * ## Example
 * ```kotlin
 * orderShipped.subscribed(
 *     defaultEmail = Frequency.immediately(),
 *     defaultSms = null,  // disabled by default
 *     defaultPush = Frequency.immediately()
 * ) { event ->
 *     setOf(event.subject.customerId)  // notify the customer
 * }
 * ```
 *
 * @param defaultEmail Default email delivery frequency (null to disable)
 * @param defaultSms Default SMS delivery frequency (null to disable)
 * @param defaultPush Default push notification delivery frequency (null to disable)
 * @param defaultInApp Default in-app notification delivery frequency
 * @param generator Function that returns the set of user IDs to notify
 */
context(handler: NotificationEndpoints<USER, UID, *, *, FrequencyCustomizableSubscriptions<USER, UID>>)
public fun <USER : HasId<UID>, UID : Comparable<UID>, T : HasId<ID>, ID : Comparable<ID>> EventDefinition<T, ID>.subscribed(
    defaultEmail: Frequency? = Frequency.immediately(),
    defaultSms: Frequency? = Frequency.immediately(),
    defaultPush: Frequency? = Frequency.immediately(),
    defaultInApp: Frequency? = Frequency.immediately(),
    generator: suspend context(ServerRuntime) (Event<T, ID>) -> Set<UID>
) {
    handler.subscriptions.setSubscribers(this, defaultEmail, defaultSms, defaultPush, defaultInApp, generator)
}