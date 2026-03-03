package com.lightningkite.lightningserver.notifications.subscriptions

import com.lightningkite.lightningserver.definition.builder.ListRegistry
import com.lightningkite.lightningserver.definition.builder.MapRegistry
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.builder.getOrRegister
import com.lightningkite.lightningserver.notifications.Frequency
import com.lightningkite.lightningserver.notifications.NotificationEndpoints
import com.lightningkite.lightningserver.notifications.ScheduledSendMethods
import com.lightningkite.lightningserver.notifications.events.Event
import com.lightningkite.lightningserver.notifications.events.EventDefinition
import com.lightningkite.lightningserver.notifications.events.EventRegistry
import com.lightningkite.lightningserver.notifications.events.UserEventType
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.lightningserver.typed.ModelRestEndpointsAndUpdatesWebsocket
import com.lightningkite.lightningserver.typed.ModelRestEndpointsAndUpdatesWebsocket.Companion.plus
import com.lightningkite.lightningserver.typed.ModelRestUpdatesWebsocket
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.SerializableProperty
import com.lightningkite.services.database.getMany
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging

public class FrequencyCustomizableSubscriptions<USER : HasId<UID>, UID : Comparable<UID>>(
    public val info: ModelInfo<USER, NotificationSendMethods<UID>, UserEventType<UID>>,
    websocketKey: SerializableProperty<NotificationSendMethods<UID>, *>? = info.serializer.fieldInApp
) : ServerBuilder(), NotificationEndpoints.Subscriptions<USER, UID> {
    private val logger: KLogger = KotlinLogging.logger("com.lightningkite.lightningserver.notifications.subscriptions.FrequencyCustomizableSubscriptions")

    private data class EventListener<USER : HasId<UID>, UID : Comparable<UID>, T : HasId<ID>, ID : Comparable<ID>>(
        val eventType: EventDefinition<USER, T, *>,
        val defaultEmail: Frequency? = Frequency.immediately(),
        val defaultSms: Frequency? = Frequency.immediately(),
        val defaultPush: Frequency? = Frequency.immediately(),
        val defaultInApp: Frequency? = Frequency.immediately(),
        val interested: suspend context(ServerRuntime) (Event<USER, T, ID>) -> Set<UID>
    )

    private val eventListeners = MapRegistry<String, ListRegistry<EventListener<USER, UID, *, *>>>()

    public fun <T : HasId<ID>, ID : Comparable<ID>> setSubscribers(
        type: EventDefinition<USER, T, ID>,
        defaultEmail: Frequency? = Frequency.immediately(),
        defaultSms: Frequency? = Frequency.immediately(),
        defaultPush: Frequency? = Frequency.immediately(),
        defaultInApp: Frequency? = Frequency.immediately(),
        interested: suspend context(ServerRuntime) (Event<USER, T, ID>) -> Set<UID>
    ) {
        eventListeners.getOrRegister(type.name, ::ListRegistry).register(EventListener(type, defaultEmail, defaultSms, defaultPush, defaultInApp, interested))
    }

    @Suppress("UNCHECKED_CAST")
    context(server: ServerRuntime)
    override suspend fun <T : HasId<ID>, ID : Comparable<ID>> subscribed(event: Event<USER, T, ID>): List<ScheduledSendMethods<UID>> = try {
        val listeners = eventListeners[event.type.name]?.let { it as List<EventListener<USER, UID, T, ID>> } ?: return emptyList()

        val grouped = listeners.map { it.interested(event) to it }
        val interested = grouped.flatMap { it.first }.toSet()

        val userSpecifiedMethods = info
            .table()
            .getMany(interested.map { UserEventType(it, event.type.untyped) })
            .associateBy { it._id.user }

        val now = now()

        interested.mapNotNull { user ->
            userSpecifiedMethods[user]
                ?.let { NotificationSendMethods(it._id, it.email, it.sms, it.push, it.inApp) }
                ?: grouped
                    .mapNotNull { pair ->
                        pair.second.takeIf { pair.first.contains(user) }
                    }
                    .takeUnless { it.isEmpty() }
                    ?.let { eventListeners ->
                        NotificationSendMethods(
                            UserEventType(user, event.type.untyped),
                            eventListeners.mapNotNull { it.defaultEmail }.minByOrNull { it.schedule(now) },
                            eventListeners.mapNotNull { it.defaultSms }.minByOrNull { it.schedule(now) },
                            eventListeners.mapNotNull { it.defaultPush }.minByOrNull { it.schedule(now) }
                        )
                    }
        }
    } catch (e: ClassCastException) {
        logger.error(e) { "Getting event listeners for notification subscriptions" }
        emptyList()
    }

    context(runtime: ServerRuntime)
    override suspend fun verifyAllDependencies(registry: EventRegistry<*>) {
        require(eventListeners.keys.containsAll(registry.keys)) {
            val missing = registry.keys - eventListeners.keys
            "Subscriptions are missing for (${missing.size}) event definitions: $missing"
        }
    }

    public val rest: ModelRestEndpointsAndUpdatesWebsocket<USER, NotificationSendMethods<UID>, UserEventType<UID>> =
        path.path("rest") include ModelRestEndpoints(info) + ModelRestUpdatesWebsocket(info, websocketKey)
}

context(handler: NotificationEndpoints<USER, UID, *, *, FrequencyCustomizableSubscriptions<USER, UID>>)
public fun <USER : HasId<UID>, UID : Comparable<UID>, T : HasId<ID>, ID : Comparable<ID>> EventDefinition<USER, T, ID>.subscribed(
    defaultEmail: Frequency? = Frequency.immediately(),
    defaultSms: Frequency? = Frequency.immediately(),
    defaultPush: Frequency? = Frequency.immediately(),
    defaultInApp: Frequency? = Frequency.immediately(),
    generator: suspend context(ServerRuntime) (Event<USER, T, ID>) -> Set<UID>
) {
    handler.subscriptions.setSubscribers(this, defaultEmail, defaultSms, defaultPush, defaultInApp, generator)
}