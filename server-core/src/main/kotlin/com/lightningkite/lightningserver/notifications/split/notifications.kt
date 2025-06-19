package com.lightningkite.lightningserver.notifications.split

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.getMany
import com.lightningkite.lightningdb.insertMany
import com.lightningkite.lightningserver.notifications.NotificationContent
import com.lightningkite.lightningserver.notifications.NotificationForUser
import com.lightningkite.lightningserver.notifications.NotificationFrequency
import com.lightningkite.lightningserver.notifications.NotificationSystemUtils
import com.lightningkite.now
import kotlinx.datetime.Instant

class NotificationEventHandler<USER : HasId<UID>, UID : Comparable<UID>, CONTENT : NotificationContent>(
    val scheduler: NotificationScheduler<USER, UID, CONTENT>,
    val subscriptions: SubscriptionManager<USER, UID>
): EventHandler<USER> {
    data class SendMethods<UID : Comparable<UID>>(
        val user: UID,
        val email: NotificationFrequency?,
        val sms: NotificationFrequency?,
        val push: NotificationFrequency?
    )

    interface SubscriptionManager<USER : HasId<UID>, UID : Comparable<UID>> {
        suspend fun <T:HasId<ID>, ID:Comparable<ID>> subscribed(event: TypedEvent<USER, T, ID>): List<SendMethods<UID>>
    }

    // _____Content Generators_____
    // These translate events into NotificationContent

    private data class ContentGenerator<USER:HasId<*>, T:HasId<*>, CONTENT : NotificationContent>(
        val type: TypedEventType<USER, T, *>,
        val generator: suspend (T) -> (USER) -> CONTENT
    )

    private val contentGenerators = HashMap<String, ContentGenerator<USER, *, CONTENT>>()

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : HasId<*>> getContent(event: TypedEvent<USER, T, *>) =
        contentGenerators[event.type.name]
            ?.let { (it as ContentGenerator<USER, T, CONTENT>).generator(event.subject) }
            ?: throw IllegalArgumentException("Event type ${event.type.name} has no content generator")

    fun <T:HasId<*>> setContent(key: TypedEventType<USER, T, *>, generator: suspend (T) -> (USER) -> CONTENT) {
        contentGenerators[key.type.name]?.let {
            if (it.type.info != key.info) NotificationSystemUtils.logger.warn("Event type '${key.name}' is overriding the content generator for event type '${it.type.name}'")
        }
        contentGenerators[key.type.name] = ContentGenerator(key, generator)
    }

    override suspend fun <T : HasId<ID>, ID : Comparable<ID>> handle(event: TypedEvent<USER, T, ID>) {
        val content = getContent(event)

        val now = now()

        val subscribed = subscriptions.subscribed(event)

        if (subscribed.isEmpty()) {
            NotificationSystemUtils.logger.debug("No subscriptions found for ${event.type.name}")
            return
        }
        else NotificationSystemUtils.logger.debug("${subscribed.size} subscriptions found for ${event.type.name}")

        val users = scheduler.users.collection()
            .getMany(subscribed.map { it.user }.toSet())
            .associateBy { it._id }

        val notifications = subscribed.mapNotNull { sub ->
            val user = users[sub.user] ?: return@mapNotNull null

            NotificationForUser2(
                event = event.toEvent(),
                user = sub.user,
                content = content(user),
                email = sub.email?.sendAt(now)?.let(::SendInfo),
                sms = sub.sms?.sendAt(now)?.let(::SendInfo),
                push = sub.push?.sendAt(now)?.let(::SendInfo)
            )
        }

        scheduler.notifications.collection().insertMany(notifications)
    }
}