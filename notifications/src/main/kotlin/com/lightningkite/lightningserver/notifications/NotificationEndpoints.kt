package com.lightningkite.lightningserver.notifications

import com.lightningkite.lightningserver.definition.builder.MapRegistry
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.notifications.events.EventEndpoints
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

public abstract class NotificationEndpoints<
        USER : HasId<UID>,
        UID : Comparable<UID>,
        CONTENT,
        SUBS : NotificationEndpoints.Subscriptions<USER, UID>,
        DISPATCH : NotificationEndpoints.Dispatcher<UID, CONTENT>
>(
    override val registry: EventRegistry<USER> = EventRegistry(),
) : EventHandler<USER>, ServerBuilder() {
    internal val logger: KLogger = KotlinLogging.logger("com.lightningkite.lightningserver.notifications.NotificationEventHandler")

    public interface Subscriptions<USER : HasId<UID>, UID : Comparable<UID>> {
        context(runtime: ServerRuntime)
        public suspend fun <T : HasId<ID>, ID : Comparable<ID>> subscribed(event: TypedEvent<USER, T, ID>): List<ScheduledSendMethods<UID>>
    }

    public interface Dispatcher<UID : Comparable<UID>, CONTENT> {
        context(runtime: ServerRuntime)
        public suspend fun dispatch(notifications: List<Notification<UID, CONTENT>>)
    }

    public abstract val users: ModelInfo<*, USER, UID>

    public abstract val dispatcher: DISPATCH
    public abstract val subscriptions: SUBS

    // _____Content Generators_____
    // These translate events into CONTENT

    private val contentGenerators = MapRegistry<String, suspend context(ServerRuntime) (TypedEvent<USER, *, *>) -> (USER) -> CONTENT>()

    @Suppress("UNCHECKED_CAST")
    context(_: ServerRuntime)
    private suspend fun <T : HasId<ID>, ID : Comparable<ID>> TypedEvent<USER, T, ID>.content() =
        contentGenerators[type.name]
            ?.let { (it as suspend context(ServerRuntime) (TypedEvent<USER, T, ID>) -> (USER) -> CONTENT)(this) }
            ?: throw NoSuchElementException("Event ${type.name} has no content generator")

    public fun <T : HasId<ID>, ID : Comparable<ID>> setContent(
        event: TypedEventType<USER, T, ID>,
        generator: suspend context(ServerRuntime) (TypedEvent<USER, T, ID>) -> (USER) -> CONTENT
    ) {
        @Suppress("UNCHECKED_CAST")
        contentGenerators.register(event.name, generator as suspend context(ServerRuntime) (TypedEvent<USER, *, *>) -> (USER) -> CONTENT)
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

context(handler: NotificationEndpoints<USER, UID, CONTENT, *, *>)
public fun <USER : HasId<UID>, UID : Comparable<UID>, CONTENT, T : HasId<ID>, ID : Comparable<ID>> TypedEventType<USER, T, ID>.content(
    generator: suspend context(ServerRuntime) (TypedEvent<USER, T, ID>) -> (USER) -> CONTENT
) {
    handler.setContent(this, generator)
}