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
import com.lightningkite.lightningserver.notifications.events.EventRegistry
import com.lightningkite.lightningserver.notifications.events.EventType
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

public open class NotificationEndpoints<
    USER : HasId<UID>, UID : Comparable<UID>,
    CONTENT,
    DISPATCH : NotificationEndpoints.Dispatcher<UID, CONTENT>,
    SUBS : NotificationEndpoints.Subscriptions<USER, UID>,
>(
    public val users: ModelInfo<*, USER, UID>,
    public val dispatcher: DISPATCH,
    public val subscriptions: SUBS,
    override val registry: EventRegistry<USER> = EventRegistry()
) : EventHandler<USER>, ServerBuilder() {
    internal val logger: KLogger = KotlinLogging.logger("com.lightningkite.lightningserver.notifications.NotificationEndpoints")

    public val content: ContentRegistry<USER, UID, CONTENT> = ContentRegistry()

    init {
        (dispatcher as? ServerBuilder)?.let { path.include(it) }
        (subscriptions as? ServerBuilder)?.let { path.path("subscriptions").module(it.withSdkInfo("SubscriptionsApi", "subscriptions")) }
    }

    context(runtime: ServerRuntime)
    override suspend fun <T : HasId<ID>, ID : Comparable<ID>> handle(event: Event<USER, T, ID>) {
        try {
            logger.debug { "Event Occurred: $event" }

            val content = content.getContent(event)

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

    public val verifyDependencies: StartupTask = path.path("verify") bind StartupTask {
        content.verifyAllDependencies(registry)
        dispatcher.verifyAllDependencies(registry)
        subscriptions.verifyAllDependencies(registry)
    }

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

    public interface DefinitionDependency {
        context(runtime: ServerRuntime)
        public suspend fun verifyAllDependencies(registry: EventRegistry<*>) {}
    }

    public interface Subscriptions<USER : HasId<UID>, UID : Comparable<UID>> : DefinitionDependency {
        context(runtime: ServerRuntime)
        public suspend fun <T : HasId<ID>, ID : Comparable<ID>> subscribed(event: Event<USER, T, ID>): List<ScheduledSendMethods<UID>>
    }

    public interface Dispatcher<UID : Comparable<UID>, CONTENT> : DefinitionDependency {
        context(runtime: ServerRuntime)
        public suspend fun dispatch(notifications: List<Notification<UID, CONTENT>>)
    }

    public class ContentRegistry<USER : HasId<UID>, UID : Comparable<UID>, CONTENT> : ServerBuilder(), DefinitionDependency {
        // _____Content Generators_____
        // These translate events into CONTENT

        private val contentGenerators = MapRegistry<String, suspend context(ServerRuntime) (Event<USER, *, *>) -> (USER) -> CONTENT>()

        @Suppress("UNCHECKED_CAST")
        context(_: ServerRuntime)
        public suspend fun <T : HasId<ID>, ID : Comparable<ID>> getContent(event: Event<USER, T, ID>): (USER) -> CONTENT =
            contentGenerators[event.type.name]
                ?.let { (it as suspend context(ServerRuntime) (Event<USER, T, ID>) -> (USER) -> CONTENT)(event) }
                ?: throw NoSuchElementException("Event ${event.type.name} has no content generator")

        public fun <T : HasId<ID>, ID : Comparable<ID>> setContent(
            event: EventDefinition<USER, T, ID>,
            generator: suspend context(ServerRuntime) (Event<USER, T, ID>) -> (USER) -> CONTENT
        ) {
            @Suppress("UNCHECKED_CAST")
            contentGenerators.register(event.name, generator as suspend context(ServerRuntime) (Event<USER, *, *>) -> (USER) -> CONTENT)
        }

        context(runtime: ServerRuntime)
        override suspend fun verifyAllDependencies(registry: EventRegistry<*>) {
            require(contentGenerators.keys.containsAll(registry.keys)) {
                val missing = registry.keys - contentGenerators.keys
                "Content is missing for (${missing.size}) event definitions: $missing"
            }
        }
    }
}

context(handler: NotificationEndpoints<USER, UID, CONTENT, *, *>)
public fun <USER : HasId<UID>, UID : Comparable<UID>, CONTENT, T : HasId<ID>, ID : Comparable<ID>> EventDefinition<USER, T, ID>.content(
    generator: suspend context(ServerRuntime) (Event<USER, T, ID>) -> (USER) -> CONTENT
) {
    handler.content.setContent(this, generator)
}