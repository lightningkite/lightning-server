package com.lightningkite.lightningserver.notifications

import com.lightningkite.lightningserver.definition.builder.MapRegistry
import com.lightningkite.lightningserver.notifications.events.EventHandler
import com.lightningkite.lightningserver.notifications.events.EventRegistry
import com.lightningkite.lightningserver.notifications.events.TypedEvent
import com.lightningkite.lightningserver.notifications.events.TypedEventType
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.getMany
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging

public open class NotificationEventHandler<USER : HasId<UID>, UID : Comparable<UID>, CONTENT>(
    private val users: ModelInfo<*, USER, UID>,
    public val dispatcher: Dispatcher<UID, CONTENT>,
    public val subscriptions: SubscriptionProvider<USER, UID>,
    override val registry: EventRegistry<USER> = EventRegistry(),
) : EventHandler<USER> {
    internal val logger: KLogger = KotlinLogging.logger("com.lightningkite.lightningserver.notifications.NotificationEventHandler")

    public interface SubscriptionProvider<USER : HasId<UID>, UID : Comparable<UID>> {
        context(runtime: ServerRuntime)
        public suspend fun <T : HasId<ID>, ID : Comparable<ID>> subscribed(event: TypedEvent<USER, T, ID>): List<ScheduledSendMethods<UID>>
    }

    public interface Dispatcher<UID : Comparable<UID>, CONTENT> {
        context(runtime: ServerRuntime)
        public suspend fun dispatch(notifications: List<Notification<UID, CONTENT>>)
    }

    // _____Content Generators_____
    // These translate events into CONTENT

    private typealias ContentGenerator<T, USER, CONTENT> = suspend context(ServerRuntime) (T) -> (USER) -> CONTENT

    private val contentGenerators = MapRegistry<String, ContentGenerator<*, USER, CONTENT>>()

    @Suppress("UNCHECKED_CAST")
    context(_: ServerRuntime)
    private suspend fun <T : HasId<*>> TypedEvent<USER, T, *>.content() =
        contentGenerators[type.name]
            ?.let { (it as ContentGenerator<T, USER, CONTENT>)(subject) }
            ?: throw NoSuchElementException("Event ${type.name} has no content generator")

    public fun <T : HasId<*>> setContent(
        event: TypedEventType<USER, T, *>,
        generator: suspend context(ServerRuntime) (T) -> (USER) -> CONTENT
    ) {
        contentGenerators.register(event.name, generator)
    }

    context(runtime: ServerRuntime)
    override suspend fun <T : HasId<ID>, ID : Comparable<ID>> handle(event: TypedEvent<USER, T, ID>) {
        try {
            logger.debug { "Event Occurred: $event" }

            val content = event.content()

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
                    event = event.toInternalEvent(),
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
}

context(handler: NotificationEventHandler<USER, UID, CONTENT>)
public fun <USER : HasId<UID>, UID : Comparable<UID>, CONTENT, T : HasId<*>> TypedEventType<USER, T, *>.content(
    generator: suspend context(ServerRuntime) (T) -> (USER) -> CONTENT
) {
    handler.setContent(this, generator)
}