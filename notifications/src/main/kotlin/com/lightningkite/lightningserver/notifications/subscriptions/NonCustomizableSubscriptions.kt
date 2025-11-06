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

public class NonCustomizableSubscriptions<USER : HasId<UID>, UID : Comparable<UID>> : NotificationEventHandler.SubscriptionProvider<USER, UID> {
    private val logger: KLogger = KotlinLogging.logger("com.lightningkite.lightningserver.notifications.subscriptions.NonCustomizableSubscriptions")

    @JvmInline
    private value class SendMethodsGenerator<USER : HasId<UID>, UID : Comparable<UID>, T : HasId<ID>, ID : Comparable<ID>>(
        val generator: suspend context(ServerRuntime) (TypedEvent<USER, T, ID>) -> List<ScheduledSendMethods<UID>>
    )

    private val eventListeners = MapRegistry<String, ListRegistry<SendMethodsGenerator<USER, UID, *, *>>>()

    public fun <T : HasId<ID>, ID : Comparable<ID>> addEventListener(
        type: TypedEventType<USER, T, ID>,
        interested: suspend context(ServerRuntime) (TypedEvent<USER, T, ID>) -> List<ScheduledSendMethods<UID>>
    ) {
        eventListeners.getOrRegister(type.name, ::ListRegistry).register(SendMethodsGenerator(interested))
    }

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

    @Suppress("UNCHECKED_CAST")
    context(runtime: ServerRuntime)
    override suspend fun <T : HasId<ID>, ID : Comparable<ID>> subscribed(event: TypedEvent<USER, T, ID>): List<ScheduledSendMethods<UID>> = try {
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
    } catch (e: ClassCastException) {
        logger.error(e) { "Getting event listeners for notification subscriptions" }
        emptyList()
    }
}