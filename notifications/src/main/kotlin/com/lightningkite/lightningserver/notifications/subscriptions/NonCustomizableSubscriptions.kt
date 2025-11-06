package com.lightningkite.lightningserver.notifications.subscriptions

import com.lightningkite.lightningserver.definition.builder.ListRegistry
import com.lightningkite.lightningserver.definition.builder.MapRegistry
import com.lightningkite.lightningserver.definition.builder.getOrRegister
import com.lightningkite.lightningserver.notifications.Frequency
import com.lightningkite.lightningserver.notifications.NotificationEventHandler
import com.lightningkite.lightningserver.notifications.ScheduledSendMethods
import com.lightningkite.lightningserver.notifications.events.TypedEvent
import com.lightningkite.lightningserver.notifications.events.TypedEventType
import com.lightningkite.lightningserver.notifications.events.UserEventType
import com.lightningkite.lightningserver.notifications.subscriptions.FrequencyCustomizableSubscriptions.EventListener
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.getMany
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Subscription provider where all subscription logic is defined programmatically.
 *
 * This is the simplest subscription model - no user customization or database storage.
 * Subscriptions are entirely defined in code via [addEventListener] calls. Use this when:
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
public class NonCustomizableSubscriptions<USER : HasId<UID>, UID : Comparable<UID>> : NotificationEventHandler.SubscriptionProvider<USER, UID> {
    private val logger: KLogger = KotlinLogging.logger("com.lightningkite.lightningserver.notifications.subscriptions.NonCustomizableSubscriptions")

    @JvmInline
    private value class SendMethodsGenerator<USER : HasId<UID>, UID : Comparable<UID>, T : HasId<ID>, ID : Comparable<ID>>(
        val generator: suspend context(ServerRuntime) (TypedEvent<USER, T, ID>) -> List<ScheduledSendMethods<UID>>
    )

    private val eventListeners = MapRegistry<String, ListRegistry<SendMethodsGenerator<USER, UID, *, *>>>()

    /**
     * Registers an event listener with full control over subscription configuration.
     *
     * The [interested] function receives an event and returns a list of [ScheduledSendMethods],
     * allowing complete customization of which users are notified and how for each event instance.
     *
     * @param type The event type to listen for
     * @param interested Function that determines interested users and their notification preferences
     */
    public fun <T : HasId<ID>, ID : Comparable<ID>> addEventListener(
        type: TypedEventType<USER, T, ID>,
        interested: suspend context(ServerRuntime) (TypedEvent<USER, T, ID>) -> List<ScheduledSendMethods<UID>>
    ) {
        eventListeners.getOrRegister(type.name, ::ListRegistry).register(SendMethodsGenerator(interested))
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
    public fun <T : HasId<ID>, ID : Comparable<ID>> addEventListener(
        type: TypedEventType<USER, T, ID>,
        email: Frequency? = Frequency.immediately(),
        sms: Frequency? = Frequency.immediately(),
        push: Frequency? = Frequency.immediately(),
        inApp: Frequency? = Frequency.immediately(),
        interested: suspend context(ServerRuntime) (TypedEvent<USER, T, ID>) -> Set<UID>
    ) {
        eventListeners.getOrRegister(type.name, ::ListRegistry).register(
            SendMethodsGenerator { event ->
                interested(event).map {
                    ScheduledSendMethods(it, email, sms, push, inApp)
                }
            }
        )
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
    override suspend fun <T : HasId<ID>, ID : Comparable<ID>> subscribed(event: TypedEvent<USER, T, ID>): List<ScheduledSendMethods<UID>>  {
        val listeners = eventListeners[event.type.name]?.let { it as List<SendMethodsGenerator<USER, UID, T, ID>> } ?: return emptyList()

        val interested = listeners.flatMap { it.generator(event) }.groupBy { it.user }

        val now = now()

        interested.map { (user, methods) ->
            ScheduledSendMethods(
                user,
                email = methods.mapNotNull { it.email }.minByOrNull { it.schedule(now) },
                sms = methods.mapNotNull { it.sms }.minByOrNull { it.schedule(now) },
                push = methods.mapNotNull { it.push }.minByOrNull { it.schedule(now) },
                inApp = methods.mapNotNull { it.inApp }.minByOrNull { it.schedule(now) },
            )
        }
    }
}
