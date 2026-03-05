package com.lightningkite.lightningserver.notifications.subscriptions

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.definition.builder.MapRegistry
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.notifications.Frequency
import com.lightningkite.lightningserver.notifications.NotificationEndpoints
import com.lightningkite.lightningserver.notifications.ScheduledSendMethods
import com.lightningkite.lightningserver.notifications.events.EventType
import com.lightningkite.lightningserver.notifications.events.Event
import com.lightningkite.lightningserver.notifications.events.EventDefinition
import com.lightningkite.lightningserver.notifications.events.EventRegistry
import com.lightningkite.lightningserver.notifications.events.UserEventType
import com.lightningkite.lightningserver.notifications.events.type
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.lightningserver.typed.ModelRestEndpointsAndUpdatesWebsocket
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.DataClassPathSelf
import com.lightningkite.services.database.EntryChange
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.SerializableProperty
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.eq
import com.lightningkite.services.database.getMany
import com.lightningkite.services.database.insertMany
import com.lightningkite.services.database.inside
import com.lightningkite.services.database.interceptCreate
import com.lightningkite.services.database.map
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

public enum class DefaultSubscriptionUpdateBehavior {
    /**
     * Only updates the `readPermissions` field of the subscription when the user changes.
     * User modifications to other fields are preserved.
     *
     * **Complexity:** Moderate. Involves finding existing subscriptions to update.
     */
    UpdateReadPermissions,

    /**
     * Completely replaces the existing subscription with the new default when the user changes.
     * Any user modifications are lost.
     *
     * **Complexity:** Low. Replaces existing subscription regardless of state.
     */
    ReplaceExistingWithDefault,

    /**
     * Attempts to preserve user modifications to the subscription, including cases where the user deleted the subscription.
     * Updates only fields that match the old default and applies new defaults where appropriate.
     *
     * **Complexity:** High. Requires comparison and finding existing subscriptions to update, more resource-intensive.
     */
    UpdateRetainingUserChanges,
}

public typealias FullyCustomizableSubscriptions<USER, UID> = FullyCustomizableSubscriptionsWithAuth<USER, USER, UID>

/**
 * Subscription provider allowing users to fully customize their notification preferences.
 *
 * This is the most flexible subscription model, allowing users to:
 * - Specify custom filter conditions (e.g., "only notify me about orders over $100")
 * - Choose delivery frequencies per channel (email, SMS, push, in-app)
 * - Enable or disable specific event types entirely
 *
 * Subscriptions are stored in the database and can be managed through the included REST endpoints.
 * System-calculated read permissions are automatically applied to ensure users only receive
 * notifications for events they have permission to see.
 *
 * ## Default Subscriptions
 * Applications can define default subscriptions for new users using [setDefaultSubscription].
 * When a user is created, these defaults are automatically inserted. The behavior when users
 * change is controlled by [DefaultSubscriptionUpdateBehavior].
 *
 * ## Endpoints
 * - REST API and updates websocket at `/rest` for subscription CRUD operations
 * 
 * ## Important Note
 * The auto-subscription updater created by this class bulk-deletes and inserts subscriptions to update them. 
 * Keep this in mind when adding signals and hooks to the `ModelInfo`.
 *
 * @param USER The user type
 * @param UID The user ID type
 * @property info Model information for subscription storage and permissions
 */
public class FullyCustomizableSubscriptionsWithAuth<AUTH : HasId<*>?, USER : HasId<UID>, UID : Comparable<UID>>(
    info: ModelInfo<AUTH, NotificationEventSubscription<UID>, UserEventType<UID>>,
    users: ModelInfo<*, USER, UID>,
    private val principal: PrincipalType<USER, UID>,
    private val events: EventRegistry,
    websocketKey: SerializableProperty<NotificationEventSubscription<UID>, *> = info.serializer.field_id,
    private val suppressRejectedAuthenticationWarnings: Boolean = false
) : NotificationEndpoints.Subscriptions<USER, UID>, ServerBuilder() {
    public val info: ModelInfo<AUTH, NotificationEventSubscription<UID>, UserEventType<UID>> =
        object : ModelInfo<AUTH, NotificationEventSubscription<UID>, UserEventType<UID>> by info {
            context(server: ServerRuntime)
            override suspend fun table(auth: AuthAccess<AUTH>): Table<NotificationEventSubscription<UID>> =
                info.table(auth)
                    .interceptCreate { subscription ->
                        val type = events.getValue(subscription._id.type.name)

                        try {
                            server.internalSerialization.json.decodeFromString(
                                type.conditionSerializer,
                                subscription.requestedFilter
                            )
                        } catch (e: Exception) {
                            throw BadRequestException("Could not decode requested subscription filter for event type: $e", cause = e)
                        }

                        subscription.copy(
                            readPermissions = type.serializedReadPermissions(auth.authOrNull)
                        )
                    }
        }

    /**
     * REST endpoints and updates websocket for managing user notification subscriptions.
     * Mounted at `/rest`. Provides CRUD operations for subscriptions.
     */
    public val rest: ModelRestEndpointsAndUpdatesWebsocket<AUTH, NotificationEventSubscription<UID>, UserEventType<UID>> =
        path.path("rest") include ModelRestEndpointsAndUpdatesWebsocket(info, websocketKey)

    private val self = DataClassPathSelf(info.serializer)

    /**
     * Configuration for a default subscription that is automatically created for new users.
     *
     * @property eventType The event type this default applies to
     * @property behavior How to handle updates when user permissions change
     * @property subscription Function that generates the default subscription for a user (or null to skip)
     */
    public data class DefaultSubscription<USER : HasId<UID>, UID : Comparable<UID>, T : HasId<*>>(
        val eventType: EventDefinition<T, *>,
        val behavior: DefaultSubscriptionUpdateBehavior = DefaultSubscriptionUpdateBehavior.UpdateReadPermissions,
        val subscription: suspend context(ServerRuntime) (USER) -> FullEventSubscription<T>?,
    )

    context(server: ServerRuntime)
    internal suspend fun <USER : HasId<UID>, UID : Comparable<UID>, T : HasId<*>> DefaultSubscription<USER, UID, T>.readPermissions(
        principal: PrincipalType<USER, UID>,
        user: USER,
    ): String = eventType.serializedReadPermissions(
        Authentication(principal, user, sessionId = null)    // NOTE: This gives full auth access to user, but that should be fine since this is an internal process
    )

    context(server: ServerRuntime)
    public suspend operator fun <USER : HasId<UID>, UID : Comparable<UID>, T : HasId<*>> DefaultSubscription<USER, UID, T>.invoke(
        principal: PrincipalType<USER, UID>,
        user: USER
    ): NotificationEventSubscription<UID>? =
        subscription(user)?.let {
            NotificationEventSubscription(
                UserEventType(user._id, eventType.untyped),
                requestedFilter = server.internalSerialization.json.encodeToString(
                    eventType.conditionSerializer,
                    it.filter
                ),
                readPermissions = readPermissions(principal, user),
                email = it.email,
                sms = it.sms,
                push = it.push
            )
        }

    private val defaultSubscriptions = MapRegistry<String, DefaultSubscription<USER, UID, *>>()

    /**
     * Retrieves the default subscription generator for an event type.
     *
     * @param type The event type to get the default subscription for
     * @return The subscription generator function, or null if no default is configured
     */
    @Suppress("UNCHECKED_CAST")
    public fun <T : HasId<*>> getDefaultSubscription(
        type: EventDefinition<T, *>
    ): (suspend context(ServerRuntime) (USER) -> FullEventSubscription<T>?)? =
        defaultSubscriptions[type.name]?.let { (it as DefaultSubscription<USER, UID, T>).subscription }

    /**
     * Registers a default subscription for an event type.
     *
     * The subscription function is called for each new user to generate their initial
     * subscription settings. Returning null from the function means no subscription
     * is created for that user.
     *
     * @param type The event type to configure
     * @param behavior How to handle updates when user permissions change
     * @param subscription Function that generates the subscription for a user
     */
    public fun <T : HasId<*>> setDefaultSubscription(
        type: EventDefinition<T, *>,
        behavior: DefaultSubscriptionUpdateBehavior = DefaultSubscriptionUpdateBehavior.UpdateReadPermissions,
        subscription: suspend context(ServerRuntime) (USER) -> FullEventSubscription<T>?
    ) {
        defaultSubscriptions.register(type.name, DefaultSubscription(type, behavior, subscription))
    }

    context(server: ServerRuntime)
    override suspend fun <T : HasId<ID>, ID : Comparable<ID>> subscribed(event: Event<T, ID>): List<ScheduledSendMethods<UID>> =
        info
            .table()
            .find(self._id.type eq event.type.untyped)
            .filter {
                try {
                    val subscribedCondition = Condition.And(
                        server.internalSerialization.json.decodeFromString(event.type.conditionSerializer, it.requestedFilter),
                        server.internalSerialization.json.decodeFromString(event.type.conditionSerializer, it.readPermissions)
                    )
                    subscribedCondition(event.subject)
                } catch (e: SerializationException) {
                    // Prevent serialization errors from breaking event
                    logger.error(e) { "Could not decode event subscription filter. Event Type: $event Subscription: $it" }
                    false
                }
            }
            .toList()

    /**
     * Default subscriptions are defined per event type and are managed in response to user changes.
     * The system updates subscriptions as follows:
     *
     * - When a new user is created, all default subscriptions are inserted for that user.
     * - When a user is deleted, all their subscriptions are removed.
     * - When a user is updated, default subscriptions are updated according to the specified [DefaultSubscriptionUpdateBehavior]
     * */
    context(server: ServerRuntime)
    public suspend fun updateDefaultSubscriptions(
        changes: List<EntryChange<USER>>,
        types: List<EventType>?,
    ) {
        val defaults = types?.mapNotNull { defaultSubscriptions[it.name] } ?: defaultSubscriptions.values

        val created = changes.filter { it.old == null && it.new != null }.mapNotNull { it.new }
        val deleted = changes.filter { it.old != null && it.new == null }.mapNotNull { it.old }
        val changed = changes.filter { it.old != null && it.new != null }

        logger.debug { "handling default subscriptions - created : ${created.size} deleted : ${deleted.size} changed : ${changed.size}" }

        val toInsert = defaults.flatMap { default ->
            created.mapNotNull { default(principal, it) }
        }

        val toRemove = defaults.flatMapTo(ArrayList()) { subscription ->
            deleted.map {
                UserEventType(it._id, subscription.eventType.untyped)
            }
        }

        val withUserChanges = buildMap {
            val newDefaults = buildMap {
                for (default in defaults.filter { it.behavior == DefaultSubscriptionUpdateBehavior.UpdateRetainingUserChanges }) {
                    for (change in changed) {
                        val user = change.new ?: continue
                        put(UserEventType(user._id, default.eventType.untyped), change.map { default(principal, it) })
                    }
                }
            }

            if (newDefaults.isNotEmpty()) {
                val inDb = info.table().getMany(newDefaults.keys).associateBy { it._id };

                for ((id, value) in newDefaults.entries) {
                    val (old, new) = value
                    val stored = inDb[id]

                    if (stored == null) {
                        // If there is no stored subscription, but the old default does exist, then the subscription was manually deleted and shouldn't be replaced.
                        if (old == null && new != null) put(id, new)   // If there is nothing stored, and there was previously no default, and there now is a default, insert it.
                        continue
                    }

                    if (new == null) {
                        if (old == stored) toRemove.add(id) // if the new default is null, and there was no user-changes, remove the subscription
                        continue
                    }

                    fun <T> keepUserChanges(get: (NotificationEventSubscription<UID>) -> T): T {
                        val s = get(stored)
                        val o = old?.let(get)
                        val n = get(new)

                        // only assign the new value if there was no change on the old default, and the new default differs from the current value.
                        return if (o == s && s != n) n else s
                    }

                    val updated = stored.copy(
                        readPermissions = new.readPermissions,
                        requestedFilter = keepUserChanges { it.requestedFilter },
                        email = keepUserChanges { it.email },
                        sms = keepUserChanges { it.sms },
                        push = keepUserChanges { it.push },
                    )

                    if (updated == stored) continue

                    put(updated._id, updated)
                }
            }
        }

        val justReadPermissions = buildMap {
            val newPermissions = buildMap {
                for (default in defaults.filter { it.behavior == DefaultSubscriptionUpdateBehavior.UpdateReadPermissions }) {
                    for ((_, new) in changed) {
                        if (new == null) continue
                        put(UserEventType(new._id, default.eventType.untyped), default.readPermissions(principal, new))
                    }
                }
            }

            if (newPermissions.isNotEmpty()) info.table().getMany(newPermissions.keys).forEach { stored ->
                val perms = newPermissions[stored._id]?.takeUnless { it == stored.readPermissions } ?: return@forEach

                put(stored._id, stored.copy(readPermissions = perms))
            }
        }

        val toReplace = buildMap {
            for (default in defaults.filter { it.behavior == DefaultSubscriptionUpdateBehavior.ReplaceExistingWithDefault }) {
                for ((_, new) in changed) {
                    if (new == null) continue
                    put(UserEventType(new._id, default.eventType.untyped), default(principal, new))
                }
            }
        }

        info.table().run {
            val removeKeys = (toRemove + withUserChanges.keys + toReplace.keys + justReadPermissions.keys).toSet()
            if (removeKeys.isNotEmpty()) deleteManyIgnoringOld(self._id inside removeKeys)

            val inserted = toInsert + withUserChanges.values + toReplace.values.filterNotNull() + justReadPermissions.values
            if (inserted.isNotEmpty()) insertMany(inserted)
        }
    }

    init {
        users.registerChangeListener { updateDefaultSubscriptions(it.changes, null) }
    }

    context(runtime: ServerRuntime)
    override suspend fun verifyAllDependencies(registry: EventRegistry) {
        require(defaultSubscriptions.keys.containsAll(registry.keys)) {
            val missing = registry.keys - defaultSubscriptions.keys
            "Default subscriptions are missing for (${missing.size}) event definitions: $missing"
        }
    }

    context(_: ServerRuntime)
    private suspend fun <T : HasId<*>, AUTH: HasId<*>?> EventDefinition<T, *>.readPermissions(
        auth: Authentication<AUTH & Any>?
    ): Condition<T> =
        when (val result = info.auth.check(auth)) {
            is AuthRequirement.Result.Accepted<*> ->
                @Suppress("UNCHECKED_CAST")     // SAFETY: We just checked that the auth will be accepted above, the type system just can't infer that over a star type-projection
                (info as ModelInfo<HasId<*>?, T, *>).permissions(AuthAccess(result.auth)).read

            is AuthRequirement.Result.Rejected -> {   // auth not accepted, no read permissions
                if (!suppressRejectedAuthenticationWarnings) logger.warn {
                    """
                        ModelInfo for event '$name' rejected auth <$auth>, read permissions rejected.
                         
                        Reason: ${result.reason}
                        
                        This means that your event's auth requirements rejected the above authentication. This is likely not intentional, as it will prevent users with the above auth from receiving `$name` notifications.
                           
                        If this is intentional or expected, you can suppress this warning by enabling the `suppressRejectedAuthenticationWarnings` flag in your `FullyCustomizableSubscriptions` constructor. 
                    """.trimIndent()
                }
                Condition.Never
            }
        }

    context(server: ServerRuntime)
    private suspend fun <T : HasId<*>, AUTH: HasId<*>?> EventDefinition<T, *>.serializedReadPermissions(
        auth: Authentication<AUTH & Any>?
    ): String =
        server.internalSerialization.json.encodeToString(
            conditionSerializer,
            this.readPermissions(auth)
        )

    public companion object {
        private val logger: KLogger = KotlinLogging.logger("com.lightningkite.lightningserver.notifications.subscriptions.FullyCustomizableSubscriptions")
    }
}

/**
 * Type-safe subscription configuration for a specific event type.
 *
 * Used in [FullyCustomizableSubscriptions] to define default subscriptions for users.
 * Unlike the database models above, this maintains type safety for the filtered entity.
 *
 * @param T The type of entity being filtered
 * @property filter Condition that determines which events match this subscription
 * @property email Email delivery frequency, or null to disable email notifications
 * @property push Push notification delivery frequency, or null to disable push notifications
 * @property sms SMS delivery frequency, or null to disable SMS notifications
 * @property inApp In-app notification delivery frequency, defaults to immediate
 */
@Serializable
public data class FullEventSubscription<T>(
    val filter: Condition<T>,
    val email: Frequency?,
    val push: Frequency?,
    val sms: Frequency?,
    val inApp: Frequency? = Frequency.immediately()
)

/**
 * DSL function to register a default subscription for this event type.
 *
 * The subscription function is called for each new user to generate their initial
 * subscription settings. Returning null means no subscription is created for that user.
 *
 * ## Example
 * ```kotlin
 * orderShipped.defaultSubscription { user ->
 *     FullEventSubscription(
 *         filter = Condition.Always,
 *         email = Frequency.immediately(),
 *         sms = null,  // disabled
 *         push = Frequency.immediately(),
 *         inApp = Frequency.immediately()
 *     )
 * }
 * ```
 *
 * @param behavior How to handle updates when user permissions change
 * @param subscription Function that generates the subscription for a user (or null to skip)
 */
context(handler: NotificationEndpoints<USER, UID, *, *, FullyCustomizableSubscriptions<USER, UID>>)
public fun <USER : HasId<UID>, UID : Comparable<UID>, T : HasId<ID>, ID : Comparable<ID>> EventDefinition<T, ID>.defaultSubscription(
    behavior: DefaultSubscriptionUpdateBehavior = DefaultSubscriptionUpdateBehavior.UpdateReadPermissions,
    subscription: suspend context(ServerRuntime) (USER) -> FullEventSubscription<T>?
) {
    handler.subscriptions.setDefaultSubscription(this, behavior, subscription)
}