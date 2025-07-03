package com.lightningkite.lightningserver.notifications

import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.getMany
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.ModelInfo
import com.lightningkite.lightningserver.events.TypedEvent
import com.lightningkite.lightningserver.events.TypedEventType
import com.lightningkite.lightningserver.exceptions.exceptionSettings
import com.lightningkite.now
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class PerEventSubscriptions<USER : HasId<UID>, UID : Comparable<UID>>(
    path: ServerPath,
    val info: ModelInfo<USER, NotificationSendMethods<UID>, UserEventType<UID>>
) :
    ServerPathGroup(path),
    NotificationEventHandler.SubscriptionManager<USER, UID>
{
    private val logger: Logger = LoggerFactory.getLogger("com.lightningkite.lightningserver.notifications.PerEventSubscriptions")

    private data class EventListener<USER:HasId<UID>, UID:Comparable<UID>, T:HasId<*>>(
        val eventType: TypedEventType<USER, T, *>,
        val defaultEmail: NotificationFrequency? = NotificationFrequency.immediately(),
        val defaultSms: NotificationFrequency? = NotificationFrequency.immediately(),
        val defaultPush: NotificationFrequency? = NotificationFrequency.immediately(),
        val interested: suspend (TypedEvent<USER, T, *>) -> Set<UID>
    )

    private val eventListeners = HashMap<String, Set<EventListener<USER, UID, *>>>()

    fun <T : HasId<*>> addEventListener(
        type: TypedEventType<USER, T, *>,
        defaultEmail: NotificationFrequency? = NotificationFrequency.immediately(),
        defaultSms: NotificationFrequency? = NotificationFrequency.immediately(),
        defaultPush: NotificationFrequency? = NotificationFrequency.immediately(),
        interested: suspend (TypedEvent<USER, T, *>) -> Set<UID>
    ) {
        val listener = EventListener(type, defaultEmail, defaultSms, defaultPush, interested)

        eventListeners[type.name]
            ?.let {
                eventListeners[type.name] = it + listener
            }
            ?: run {
                eventListeners[type.name] = setOf(listener)
            }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : HasId<ID>, ID : Comparable<ID>> subscribed(event: TypedEvent<USER, T, ID>): List<NotificationEventHandler.SendMethods<UID>> = try {
        val listeners = eventListeners[event.type.name]?.let { it as Set<EventListener<USER, UID, T>> } ?: return emptyList()

        val grouped = listeners.map { it.interested(event) to it }
        val interested = grouped.flatMap { it.first }.toSet()

        val userSpecifiedMethods = info
            .collection()
            .getMany(interested.map { UserEventType(it, event.type.type) })
            .associateBy { it._id.user }

        val now = now()
        interested.map { user ->
            userSpecifiedMethods[user]
                ?.let { NotificationEventHandler.SendMethods(it._id.user, it.email, it.sms, it.push) }
                ?: grouped
                    .mapNotNull { pair ->
                        pair.second.takeIf { pair.first.contains(user) }
                    }
                    .let { eventListeners ->
                        NotificationEventHandler.SendMethods(
                            user,
                            eventListeners.mapNotNull { it.defaultEmail }.minByOrNull { it.sendAt(now) },
                            eventListeners.mapNotNull { it.defaultSms }.minByOrNull { it.sendAt(now) },
                            eventListeners.mapNotNull { it.defaultPush }.minByOrNull { it.sendAt(now) }
                        )
                    }
        }
    } catch (e: ClassCastException) {
        exceptionSettings().report(e, "Getting event listeners for notification subscriptions")
        emptyList()
    }
}