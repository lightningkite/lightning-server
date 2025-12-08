package com.lightningkite.lightningserver.notifications.subscriptions

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.definition.builder.MapRegistry
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.notifications.NotificationEventHandler
import com.lightningkite.lightningserver.notifications.ScheduledSendMethods
import com.lightningkite.lightningserver.notifications.events.EventType
import com.lightningkite.lightningserver.notifications.events.TypedEvent
import com.lightningkite.lightningserver.notifications.events.TypedEventType
import com.lightningkite.lightningserver.notifications.events.UserEventType
import com.lightningkite.lightningserver.notifications.events.type
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.DataClassPathSelf
import com.lightningkite.services.database.EntryChange
import com.lightningkite.services.database.HasId
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

public class FullyCustomizableSubscriptions<USER : HasId<UID>, UID : Comparable<UID>>(
    info: ModelInfo<USER, NotificationEventSubscription<UID>, UserEventType<UID>>,
    users: ModelInfo<USER, USER, UID>,
    private val principal: PrincipalType<USER, UID>,
    private val events: Map<String, TypedEventType<USER, *, *>>
) : NotificationEventHandler.SubscriptionProvider<USER, UID>, ServerBuilder() {
    public val info: ModelInfo<USER, NotificationEventSubscription<UID>, UserEventType<UID>> =
        object : ModelInfo<USER, NotificationEventSubscription<UID>, UserEventType<UID>> by info {
            context(server: ServerRuntime)
            override suspend fun table(auth: AuthAccess<USER>): Table<NotificationEventSubscription<UID>> =
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
                            readPermissions = type.serializedReadPermissions(auth)
                        )
                    }
        }

    private val logger: KLogger = KotlinLogging.logger("com.lightningkite.lightningserver.notifications.subscriptions.FullyCustomizableSubscriptions")

    public val rest: ModelRestEndpoints<USER, NotificationEventSubscription<UID>, UserEventType<UID>> =
        path.path("rest") include ModelRestEndpoints(info)


    private val self = DataClassPathSelf(info.serializer)

    public data class DefaultSubscription<USER : HasId<UID>, UID : Comparable<UID>, T : HasId<*>>(
        val eventType: TypedEventType<USER, T, *>,
        val behavior: DefaultSubscriptionUpdateBehavior = DefaultSubscriptionUpdateBehavior.UpdateReadPermissions,
        val subscription: suspend (USER) -> Subscription<T>?,
    ) {
        context(server: ServerRuntime)
        internal suspend fun readPermissions(
            principal: PrincipalType<USER, UID>,
            user: USER,
        ) = server.internalSerialization.json.encodeToString(
            eventType.conditionSerializer,
            eventType.info.permissions(
                AuthAccess(Authentication(
                    principal,
                    user,
                    sessionId = null,
                ))
            ).read
        )

        context(server: ServerRuntime)
        public suspend operator fun invoke(
            principal: PrincipalType<USER, UID>,
            user: USER
        ): NotificationEventSubscription<UID>? =
            subscription(user)?.let {
                NotificationEventSubscription(
                    UserEventType(user._id, eventType.type),
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
    }

    private val defaultSubscriptions = MapRegistry<String, DefaultSubscription<USER, UID, *>>()

    @Suppress("UNCHECKED_CAST")
    public fun <T : HasId<*>> getDefaultSubscription(
        type: TypedEventType<USER, T, *>
    ): (suspend (USER) -> Subscription<T>?)? =
        defaultSubscriptions[type.name]?.let { (it as DefaultSubscription<USER, UID, T>).subscription }

    public fun <T : HasId<*>> setDefaultSubscription(
        type: TypedEventType<USER, T, *>,
        behavior: DefaultSubscriptionUpdateBehavior = DefaultSubscriptionUpdateBehavior.UpdateReadPermissions,
        subscription: suspend (USER) -> Subscription<T>?
    ) {
        defaultSubscriptions.register(type.name, DefaultSubscription(type, behavior, subscription))
    }

    context(server: ServerRuntime)
    override suspend fun <T : HasId<ID>, ID : Comparable<ID>> subscribed(event: TypedEvent<USER, T, ID>): List<ScheduledSendMethods<UID>> =
        info
            .table()
            .find(self._id.type eq event.type.type)
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
                UserEventType(it._id, subscription.eventType.type)
            }
        }

        val withUserChanges = buildMap {
            val newDefaults = buildMap {
                for (default in defaults.filter { it.behavior == DefaultSubscriptionUpdateBehavior.UpdateRetainingUserChanges }) {
                    for (change in changed) {
                        val user = change.new ?: continue
                        put(UserEventType(user._id, default.eventType.type), change.map { default(principal, it) })
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
                        put(UserEventType(new._id, default.eventType.type), default.readPermissions(principal, new))
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
                    put(UserEventType(new._id, default.eventType.type), default(principal, new))
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

    context(server: ServerRuntime)
    private suspend fun <T : HasId<*>> TypedEventType<USER, T, *>.serializedReadPermissions(auth: AuthAccess<USER>) =
        server.internalSerialization.json.encodeToString(conditionSerializer, info.permissions(auth).read)
}