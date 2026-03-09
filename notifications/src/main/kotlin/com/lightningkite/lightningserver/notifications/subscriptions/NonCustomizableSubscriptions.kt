package com.lightningkite.lightningserver.notifications.subscriptions

import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.builder.MapRegistry
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.notifications.Frequency
import com.lightningkite.lightningserver.notifications.NotificationEndpoints
import com.lightningkite.lightningserver.notifications.ScheduledSendMethods
import com.lightningkite.lightningserver.notifications.events.Event
import com.lightningkite.lightningserver.notifications.events.EventDefinition
import com.lightningkite.lightningserver.notifications.events.EventRegistry
import com.lightningkite.lightningserver.notifications.events.EventRegistry.Companion.events
import com.lightningkite.lightningserver.notifications.events.EventType
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.services.database.HasId

private typealias SendMethodsGenerator<UID, T, ID> = suspend context(ServerRuntime) (Event<T, ID>) -> List<ScheduledSendMethods<UID>>

/**
 * Subscription provider where all subscription logic is defined programmatically.
 *
 * This is the simplest subscription model - no user customization or database storage.
 * Subscriptions are entirely defined in code via [setSubscribed] calls. Use this when:
 * - Subscriptions are mandatory or universal (e.g., all admins get certain notifications)
 * - You want complete control over notification delivery logic
 * - You don't want to maintain subscription state in the database
 *
 * When an event occurs, registered event listeners determine which users should be notified
 * and with what frequencies. Multiple listeners for the same event type are supported, and
 * their results are merged (taking the earliest scheduled time for each channel when multiple
 * listeners target the same user).
 *
 * @param USER The user type
 * @param UID The user ID type
 */
public class NonCustomizableSubscriptions<USER : HasId<UID>, UID : Comparable<UID>>(
    public val defaultEmail: Frequency? = Frequency.immediately(),
    public val defaultSms: Frequency? = Frequency.immediately(),
    public val defaultPush: Frequency? = Frequency.immediately(),
    public val defaultInApp: Frequency? = Frequency.immediately(),
) : NotificationEndpoints.Subscriptions<USER, UID>, ServerBuilder() {
//    private val logger: KLogger = KotlinLogging.logger("com.lightningkite.lightningserver.notifications.subscriptions.NonCustomizableSubscriptions")

    private val eventListeners = MapRegistry<EventType.Name, SendMethodsGenerator<UID, *, *>>()

    /**
     * Registers an event listener with full control over subscription configuration.
     *
     * The [interested] function receives an event and returns a list of [ScheduledSendMethods],
     * allowing complete customization of which users are notified and how for each event instance.
     *
     * @param type The event type to listen for
     * @param interested Function that determines interested users and their notification preferences
     */
    public fun <T : HasId<ID>, ID : Comparable<ID>> setSubscribedDirect(
        type: EventDefinition<T, ID>,
        interested: suspend context(ServerRuntime) (Event<T, ID>) -> List<ScheduledSendMethods<UID>>
    ) {
        @Suppress("UNCHECKED_CAST")
        eventListeners.register(type.name, interested as SendMethodsGenerator<UID, *, *>)
    }

    /**
     * Registers an event listener with fixed delivery frequencies.
     *
     * The [interested] function determines which users should be notified, and all matching
     * users receive notifications with the same frequency settings for each channel.
     *
     * @param type The event type to listen for
     * @param email Email delivery frequency for all interested users (null to disable)
     * @param sms SMS delivery frequency for all interested users (null to disable)
     * @param push Push notification delivery frequency for all interested users (null to disable)
     * @param inApp In-app notification delivery frequency for all interested users (defaults to immediate)
     * @param interested Function that determines which user IDs should be notified
     */
    public inline fun <T : HasId<ID>, ID : Comparable<ID>> setSubscribed(
        type: EventDefinition<T, ID>,
        email: Frequency? = this.defaultEmail,
        sms: Frequency? = this.defaultSms,
        push: Frequency? = this.defaultPush,
        inApp: Frequency? = this.defaultInApp,
        crossinline interested: suspend context(ServerRuntime) (Event<T, ID>) -> Set<UID>
    ) {
        setSubscribedDirect(type) { event ->
            interested(event).map {
                ScheduledSendMethods(it, email, sms, push, inApp)
            }
        }
    }

    /**
     * Determines which users should receive notifications for the given event.
     *
     * Calls all registered listeners for this event type and merges their results.
     * When multiple listeners specify different frequencies for the same user and channel,
     * the earliest scheduled time is used (enabling the most urgent notification to take precedence).
     *
     * @param event The event occurrence
     * @return List of user IDs and their notification preferences
     */
    @Suppress("UNCHECKED_CAST")
    context(runtime: ServerRuntime)
    override suspend fun <T : HasId<ID>, ID : Comparable<ID>> subscribed(event: Event<T, ID>): List<ScheduledSendMethods<UID>> {
        val interested = eventListeners[event.type.name]?.let { it as SendMethodsGenerator<UID, T, ID> } ?: return emptyList()

        return interested(event)
    }

    private val verify = path.path("verify") bind StartupTask {
        val registry = serverRuntime.server.events
        require(eventListeners.keys.containsAll(registry.keys)) {
            val missing = registry.keys - eventListeners.keys
            "Subscriptions are missing for (${missing.size}) event definitions: $missing"
        }
    }
}

/**
 * DSL function to register a subscriber generator with full control over notification configuration.
 *
 * The generator receives an event and returns a list of [ScheduledSendMethods], allowing
 * different users to receive different frequency settings for the same event.
 *
 * ## Example
 * ```kotlin
 * orderShipped.subscribedDirect { event ->
 *     val customer = ScheduledSendMethods(
 *         user = event.subject.customerId,
 *         email = Frequency.immediately(),
 *         push = Frequency.immediately()
 *     )
 *     val seller = ScheduledSendMethods(
 *         user = event.subject.sellerId,
 *         email = Frequency.daily(9, 0),  // daily digest
 *         push = null  // no push for sellers
 *     )
 *     listOf(customer, seller)
 * }
 * ```
 *
 * @param interested Function that returns the list of users and their notification preferences
 */
context(handler: NotificationEndpoints<USER, UID, *, *, NonCustomizableSubscriptions<USER, UID>>)
public fun <USER : HasId<UID>, UID : Comparable<UID>, T : HasId<ID>, ID : Comparable<ID>> EventDefinition<T, ID>.subscribedDirect(
    interested: suspend context(ServerRuntime) (Event<T, ID>) -> List<ScheduledSendMethods<UID>>
) {
    handler.subscriptions.setSubscribedDirect(this, interested)
}

/**
 * DSL function to register a subscriber generator with fixed delivery frequencies.
 *
 * The generator determines which users should be notified, and all matched users
 * receive notifications with the same frequency settings.
 *
 * ## Example
 * ```kotlin
 * orderShipped.subscribed(
 *     email = Frequency.immediately(),
 *     sms = null,  // disabled
 *     push = Frequency.immediately()
 * ) { event ->
 *     setOf(event.subject.customerId)
 * }
 * ```
 *
 * @param email Email delivery frequency for all interested users (null to disable)
 * @param sms SMS delivery frequency for all interested users (null to disable)
 * @param push Push notification delivery frequency for all interested users (null to disable)
 * @param inApp In-app notification delivery frequency for all interested users
 * @param interested Function that returns the set of user IDs to notify
 */
context(handler: NotificationEndpoints<USER, UID, *, *, NonCustomizableSubscriptions<USER, UID>>)
public inline fun <USER : HasId<UID>, UID : Comparable<UID>, T : HasId<ID>, ID : Comparable<ID>> EventDefinition<T, ID>.subscribed(
    email: Frequency? = handler.subscriptions.defaultEmail,
    sms: Frequency? = handler.subscriptions.defaultSms,
    push: Frequency? = handler.subscriptions.defaultPush,
    inApp: Frequency? = handler.subscriptions.defaultInApp,
    crossinline interested: suspend context(ServerRuntime) (Event<T, ID>) -> Set<UID>
) {
    handler.subscriptions.setSubscribed(this, email, sms, push, inApp, interested)
}
