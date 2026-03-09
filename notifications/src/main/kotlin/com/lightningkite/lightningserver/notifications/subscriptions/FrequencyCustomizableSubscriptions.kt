package com.lightningkite.lightningserver.notifications.subscriptions

import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.id
import com.lightningkite.lightningserver.auth.subscope
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.builder.MapRegistry
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.notifications.Frequency
import com.lightningkite.lightningserver.notifications.NotificationEndpoints
import com.lightningkite.lightningserver.notifications.ScheduledSendMethods
import com.lightningkite.lightningserver.notifications.events.Event
import com.lightningkite.lightningserver.notifications.events.EventDefinition
import com.lightningkite.lightningserver.notifications.events.EventRegistry.Companion.events
import com.lightningkite.lightningserver.notifications.events.EventType
import com.lightningkite.lightningserver.notifications.events.UserEventType
import com.lightningkite.lightningserver.notifications.query
import com.lightningkite.lightningserver.notifications.queryBy
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.lightningserver.typed.ModelRestEndpointsAndUpdatesWebsocket
import com.lightningkite.lightningserver.typed.auth
import com.lightningkite.lightningserver.typed.explicitApiHttpHandler
import com.lightningkite.lightningserver.typed.invoke
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.Query
import com.lightningkite.services.database.SerializableProperty
import com.lightningkite.services.database.condition
import com.lightningkite.services.database.get
import com.lightningkite.services.database.getMany
import com.lightningkite.services.database.query
import com.lightningkite.services.database.typeParametersSerializersOrNull
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.internal.GeneratedSerializer

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
 * @property defaultEmail The default frequency for emails when constructing events
 * @property defaultSms The default frequency for sms when constructing events
 * @property defaultPush The default frequency for push when constructing events
 * @property defaultInApp The default frequency for inApp when constructing events
 * @property websocketKey The key provided to [com.lightningkite.lightningserver.typed.ModelRestUpdatesWebsocket] to optimize updates (defaults to `inApp`)
 * @property userIdSerializer The serializer for the user's ID, only needed if the type parameter serializer cannot be inferred automatically.
 */
public class FrequencyCustomizableSubscriptions<USER : HasId<UID>, UID : Comparable<UID>>(
    public val info: ModelInfo<USER, NotificationSendMethods<UID>, UserEventType<UID>>,
    public val defaultEmail: Frequency? = Frequency.immediately(),
    public val defaultSms: Frequency? = Frequency.immediately(),
    public val defaultPush: Frequency? = Frequency.immediately(),
    public val defaultInApp: Frequency? = Frequency.immediately(),
    websocketKey: SerializableProperty<NotificationSendMethods<UID>, *>? = info.serializer.fieldInApp,
    userIdSerializer: KSerializer<UID>? = null
) : ServerBuilder(), NotificationEndpoints.Subscriptions<USER, UID> {
    private val logger: KLogger = KotlinLogging.logger("com.lightningkite.lightningserver.notifications.subscriptions.FrequencyCustomizableSubscriptions")

    public data class Subscriptions<UID : Comparable<UID>, T : HasId<ID>, ID : Comparable<ID>>(
        val eventType: EventDefinition<T, *>,
        val defaultEmail: Frequency? = Frequency.immediately(),
        val defaultSms: Frequency? = Frequency.immediately(),
        val defaultPush: Frequency? = Frequency.immediately(),
        val defaultInApp: Frequency? = Frequency.immediately(),
        val interested: suspend context(ServerRuntime) (Event<T, ID>) -> Set<UID>
    ) {
        public fun toSendMethods(user: UID): NotificationSendMethods<UID> = NotificationSendMethods(
            UserEventType(user, eventType.name),
            email = defaultEmail,
            push = defaultPush,
            sms = defaultSms,
            inApp = defaultInApp
        )
    }

    private val eventListeners = MapRegistry<EventType.Name, Subscriptions<UID, *, *>>()

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
        defaultEmail: Frequency? = this.defaultEmail,
        defaultSms: Frequency? = this.defaultSms,
        defaultPush: Frequency? = this.defaultPush,
        defaultInApp: Frequency? = this.defaultInApp,
        interested: suspend context(ServerRuntime) (Event<T, ID>) -> Set<UID>
    ) {
        eventListeners.register(type.name, Subscriptions(type, defaultEmail, defaultSms, defaultPush, defaultInApp, interested))
    }

    @Suppress("UNCHECKED_CAST")
    context(_: ServerRuntime) // making this runtime-only so that we're guaranteed to have registered subscribers already
    public fun <T : HasId<ID>, ID : Comparable<ID>> getSubscribers(type: EventDefinition<T, ID>): Subscriptions<UID, T, ID> {
        return eventListeners[type.name]?.let { it as Subscriptions<UID, T, ID> } ?: throw IllegalStateException("No subscribers registered for event type $type")
    }

    @Suppress("UNCHECKED_CAST")
    context(server: ServerRuntime)
    override suspend fun <T : HasId<ID>, ID : Comparable<ID>> subscribed(event: Event<T, ID>): List<ScheduledSendMethods<UID>> = try {
        val subscriptions = getSubscribers(event.type)

        val interested = subscriptions.interested(event)

        val userSpecifiedMethods = info
            .table()
            .getMany(interested.map { UserEventType(it, event.type.name) })
            .associateBy { it._id.user }

        interested.map { user ->
            userSpecifiedMethods[user] ?: subscriptions.toSendMethods(user)
        }
    } catch (e: ClassCastException) {
        logger.error(e) { "Getting event listeners for notification subscriptions" }
        emptyList()
    }

    private val verify = path.path("verify") bind StartupTask {
        val registry = serverRuntime.server.events

        require(eventListeners.keys.containsAll(registry.keys)) {
            val missing = registry.keys - eventListeners.keys
            "Subscriptions are missing for (${missing.size}) event definitions: $missing"
        }
    }

    /**
     * REST and WebSocket endpoints for managing delivery frequency preferences.
     * Mounted at `/rest`. Provides CRUD operations and real-time updates for preferences.
     */
    public val rest: ModelRestEndpointsAndUpdatesWebsocket<USER, NotificationSendMethods<UID>, UserEventType<UID>> =
        path.path("rest") include ModelRestEndpointsAndUpdatesWebsocket(info, websocketKey)

    @Suppress("UNCHECKED_CAST")
    private val returnTypeSerializer = NotificationSendMethods.DbOrDefault.serializer(
        userIdSerializer ?: info.serializer.typeParametersSerializersOrNull()?.firstOrNull() as? KSerializer<UID>
            ?: throw IllegalStateException("Cannot infer serializer for user ID (`UID`). Please specify it explicitly with the `userIdSerializer` parameter.")
    )

    private fun NotificationSendMethods<UID>.isDefault(isDefault: Boolean) = NotificationSendMethods.DbOrDefault(this, isDefault)

    public val getSendMethodsForEvent: ApiHttpHandler<PathSpec1<EventType.Name>, USER, Unit, NotificationSendMethods.DbOrDefault<UID>> =
        path.arg<EventType.Name>("event").get bind explicitApiHttpHandler(
            summary = "Get Subscription For Event",
            description = "Retrieves the users subscription for the specified event type, or the default settings if the user has not provided a subscription yet.",
            auth = info.auth.subscope(ModelInfo.Scopes.read),
            inputType = Unit.serializer(),
            outputType = returnTypeSerializer,
            implementation = { _: Unit ->
                val def = serverRuntime.server.events[request.arg1] ?: throw NotFoundException()

                info.table(this)
                    .get(UserEventType(auth.id, request.arg1))
                    ?.let {
                        return@explicitApiHttpHandler it.isDefault(false)
                    }

                val default = getSubscribers(def).toSendMethods(auth.id)

                if (!info.permissions(this).read(default)) throw NotFoundException()

                default.isDefault(true)
            }
        )

    public val querySendMethods: ApiHttpHandler<PathSpec0, USER, Query<NotificationSendMethods<UID>>, List<NotificationSendMethods.DbOrDefault<UID>>> =
        path.path("query").post bind explicitApiHttpHandler(
            summary = "Query Event Subscriptions",
            description = "Retrieves all of the users subscriptions (specified or default) for the specified query",
            auth = info.auth.subscope(ModelInfo.Scopes.read),
            inputType = Query.serializer(info.serializer),
            outputType = ListSerializer(returnTypeSerializer),
            implementation = { query: Query<NotificationSendMethods<UID>> ->
                val events = serverRuntime.server.events

                val specified = info.table(this).query(query).map { it.isDefault(false) }.toList()

                val missing = events.keys - specified.mapTo(HashSet()) { it.subscription._id.event }

                val defaults = if (missing.isNotEmpty()) {
                    val permissions = info.permissions(this)
                    missing.mapNotNull { name ->
                        getSubscribers(events.getValue(name))
                            .toSendMethods(auth.id)
                            .takeIf { permissions.read(it) }
                            ?.let(permissions::mask)
                            ?.isDefault(true)
                    }
                } else emptyList()

                (specified + defaults)
                    .asSequence()
                    .queryBy(query) { it.subscription }
                    .toList()
            }
        )

    public val listSendMethods: ApiHttpHandler<PathSpec0, USER, Unit, List<NotificationSendMethods.DbOrDefault<UID>>> =
        path.get bind explicitApiHttpHandler(
            summary = "List Event Subscriptions",
            description = "Retrieves all of the users subscriptions (specified or default)",
            auth = info.auth.subscope(ModelInfo.Scopes.read),
            inputType = Unit.serializer(),
            outputType = ListSerializer(returnTypeSerializer),
            implementation = { _: Unit ->
                querySendMethods(Query(limit = Int.MAX_VALUE))
            }
        )
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
    defaultEmail: Frequency? = handler.subscriptions.defaultEmail,
    defaultSms: Frequency? = handler.subscriptions.defaultSms,
    defaultPush: Frequency? = handler.subscriptions.defaultPush,
    defaultInApp: Frequency? = handler.subscriptions.defaultInApp,
    generator: suspend context(ServerRuntime) (Event<T, ID>) -> Set<UID>
) {
    handler.subscriptions.setSubscribers(this, defaultEmail, defaultSms, defaultPush, defaultInApp, generator)
}