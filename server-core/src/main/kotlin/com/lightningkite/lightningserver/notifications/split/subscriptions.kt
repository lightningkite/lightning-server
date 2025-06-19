package com.lightningkite.lightningserver.notifications.split

import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.EntryChange
import com.lightningkite.lightningdb.FieldCollection
import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.eq
import com.lightningkite.lightningdb.getMany
import com.lightningkite.lightningdb.insertMany
import com.lightningkite.lightningdb.inside
import com.lightningkite.lightningdb.interceptCreate
import com.lightningkite.lightningdb.map
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.ModelInfo
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.exceptionSettings
import com.lightningkite.lightningserver.notifications.EventType
import com.lightningkite.lightningserver.notifications.NotificationFrequency
import com.lightningkite.lightningserver.notifications.NotificationSystemUtils
import com.lightningkite.lightningserver.notifications.SerializedCondition
import com.lightningkite.lightningserver.notifications.UserEventType
import com.lightningkite.lightningserver.notifications.type
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.typed.AuthAccessor
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set

@Serializable
@GenerateDataClassPaths
data class SubscriptionInfo<T>(
    val filter: Condition<T> = Condition.Always,
    val email: NotificationFrequency? = NotificationFrequency.immediately(),
    val sms: NotificationFrequency? = NotificationFrequency.immediately(),
    val push: NotificationFrequency? = NotificationFrequency.immediately()
) {
    constructor(
        filter: Condition<T> = Condition.Always,
        frequency: NotificationFrequency = NotificationFrequency.immediately(),
        email: Boolean = true,
        sms: Boolean = true,
        push: Boolean = true
    ) : this(filter, frequency.takeIf { email }, frequency.takeIf { sms }, frequency.takeIf { push })
}

@Serializable
@GenerateDataClassPaths
data class NotificationEventSubscription<UID : Comparable<UID>>(
    override val _id: UserEventType<UID>,
    val requestedFilter: SerializedCondition,  // JSON of Condition<T> (T is model type of event)
    val readPermissions: SerializedCondition,  // Calculated permissions for T for user
    val email: NotificationFrequency?,
    val push: NotificationFrequency?,
    val sms: NotificationFrequency?,
): HasId<UserEventType<UID>>

enum class DefaultSubscriptionBehavior {
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

class CustomizableFilterSubscriptionManager<USER:HasId<UID>, UID:Comparable<UID>>(
    path: ServerPath,
    info: ModelInfo<USER, NotificationEventSubscription<UID>, UserEventType<UID>>,
    users: ModelInfo<USER, USER, UID>,
    val eventRegistry: EventRegistry<USER>
) :
    InfoAndEndpoints<USER, UID, NotificationEventSubscription<UID>, UserEventType<UID>>(path, info),
    NotificationEventHandler.SubscriptionManager<USER, UID>
{
    private data class DefaultSubscription<USER:HasId<UID>, UID:Comparable<UID>, T:HasId<*>>(
        val eventType: TypedEventType<USER, T, *>,
        val behavior: DefaultSubscriptionBehavior = DefaultSubscriptionBehavior.UpdateReadPermissions,
        val subscription: suspend (USER) -> SubscriptionInfo<T>?,
    ) {
        /**NOTE: This uses `AuthAccessor.test()`, which gives full access to the user. This should be fine for notification purposes.*/
        suspend fun readPermissions(user: USER) = Serialization.json.encodeToString(eventType.conditionSerializer, eventType.info.permissions(AuthAccessor.test(user)).read)

        suspend operator fun invoke(user: USER) = subscription(user)?.let {
            NotificationEventSubscription(
                UserEventType(user._id, eventType.type),
                requestedFilter = Serialization.json.encodeToString(eventType.conditionSerializer, it.filter),
                readPermissions = readPermissions(user),
                email = it.email,
                sms = it.sms,
                push = it.push
            )
        }
    }

    private val defaultSubscriptions = HashMap<String, DefaultSubscription<USER, UID, *>>()

    @Suppress("UNCHECKED_CAST")
    fun <T : HasId<*>> getDefaultSubscription(type: TypedEventType<USER, T, *>) =
        defaultSubscriptions[type.name]
            ?.let { (it as DefaultSubscription<USER, UID, T>).subscription }

    fun <T : HasId<*>> setDefaultSubscription(
        type: TypedEventType<USER, T, *>,
        behavior: DefaultSubscriptionBehavior = DefaultSubscriptionBehavior.UpdateReadPermissions,
        subscription: suspend (USER) -> SubscriptionInfo<T>?
    ) {
        defaultSubscriptions[type.name]?.let {
            if (it.eventType.info != type.info) NotificationSystemUtils.logger.warn(
                "Default subscription for event type '${it.eventType.name}' is overridden. Old model: ${it.eventType.info.collectionName}  New model: ${type.info.collectionName}"
            )
        }
        defaultSubscriptions[type.name] = DefaultSubscription(type, behavior, subscription)
    }

    override suspend fun <T : HasId<ID>, ID : Comparable<ID>> subscribed(event: TypedEvent<USER, T, ID>): List<NotificationEventHandler.SendMethods<UID>> {
        val subscriptions = collection()
            .find(condition { it._id.type eq event.type.type })
            .filter {
                try {
                    val subscribedCondition = Condition.And(
                        Serialization.json.decodeFromString(event.type.conditionSerializer, it.requestedFilter),
                        Serialization.json.decodeFromString(event.type.conditionSerializer, it.readPermissions)
                    )
                    subscribedCondition(event.subject)
                } catch (e: SerializationException) {
                    // Prevent serialization errors from breaking event
                    exceptionSettings().report(e, "Could not decode event subscription filter. Event Type: $event Subscription: $it")
                    false
                }
            }
            .toList()

        return subscriptions.map {
            NotificationEventHandler.SendMethods(
                user = it._id.user,
                email = it.email,
                sms = it.sms,
                push = it.push
            )
        }
    }

    /**
    // Default subscriptions are defined per event type and are managed in response to user changes.
    // The system updates subscriptions as follows:
    //
    // - When a new user is created, all default subscriptions are inserted for that user.
    // - When a user is deleted, all their default subscriptions are removed.
    // - When a user is updated, default subscriptions are updated according to the specified [DefaultSubscriptionBehavior]
     */
    suspend fun updateDefaultSubscriptions(
        changes: List<EntryChange<USER>>,
        types: List<EventType>?,
    ) {
        val defaults = types?.mapNotNull { defaultSubscriptions[it.name] } ?: defaultSubscriptions.values

        val created = changes.filter { it.old == null && it.new != null }.mapNotNull { it.new }
        val deleted = changes.filter { it.old != null && it.new == null }.mapNotNull { it.old }
        val changed = changes.filter { it.old != null && it.new != null }

        NotificationSystemUtils.logger.debug("handling default subscriptions - created : ${created.size} deleted : ${deleted.size} changed : ${changed.size}")

        val toInsert = defaults.flatMap { default ->
            created.mapNotNull { default(it) }
        }

        val toRemove = defaults.flatMapTo(ArrayList()) { subscription ->
            deleted.map {
                UserEventType(it._id, subscription.eventType.type)
            }
        }

        val withUserChanges = buildMap {
            val newDefaults = buildMap {
                for (default in defaults.filter { it.behavior == DefaultSubscriptionBehavior.UpdateRetainingUserChanges }) {
                    for (change in changed) {
                        val user = change.new ?: continue
                        put(UserEventType(user._id, default.eventType.type), change.map { default(it) })
                    }
                }
            }

            if (newDefaults.isNotEmpty()) {
                val inDb = collection().getMany(newDefaults.keys).associateBy { it._id };

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
                for (default in defaults.filter { it.behavior == DefaultSubscriptionBehavior.UpdateReadPermissions }) {
                    for ((_, new) in changed) {
                        if (new == null) continue
                        put(UserEventType(new._id, default.eventType.type), default.readPermissions(new))
                    }
                }
            }

            if (newPermissions.isNotEmpty()) collection().getMany(newPermissions.keys).forEach { stored ->
                val perms = newPermissions[stored._id]?.takeUnless { it == stored.readPermissions } ?: return@forEach

                put(stored._id, stored.copy(readPermissions = perms))
            }
        }

        val toReplace = buildMap {
            for (default in defaults.filter { it.behavior == DefaultSubscriptionBehavior.ReplaceExistingWithDefault }) {
                for ((_, new) in changed) {
                    if (new == null) continue
                    put(UserEventType(new._id, default.eventType.type), default(new))
                }
            }
        }

        collection().run {
            val removeKeys = (toRemove + withUserChanges.keys + toReplace.keys + justReadPermissions.keys).toSet()
            if (removeKeys.isNotEmpty()) deleteManyIgnoringOld(condition { it._id inside removeKeys })

            val inserted = toInsert + withUserChanges.values + toReplace.values.filterNotNull() + justReadPermissions.values
            if (inserted.isNotEmpty()) insertMany(inserted)
        }
    }

    init {
        users.registerChangeListener { updateDefaultSubscriptions(it.changes, null) }
    }

    private suspend fun <T : HasId<*>> TypedEventType<USER, T, *>.serializedReadPermissions(auth: AuthAccessor<USER>) =
        Serialization.json.encodeToString(conditionSerializer, info.permissions(auth).read)

    override suspend fun collection(auth: AuthAccessor<USER>): FieldCollection<NotificationEventSubscription<UID>> = super.collection(auth).interceptCreate { subscription ->
        val type = eventRegistry[subscription._id.type]

        try {
            Serialization.json.decodeFromString(type.conditionSerializer, subscription.requestedFilter)
        } catch (e: Exception) {
            throw BadRequestException("Could not decode requested subscription filter for event type: $e", cause = e)
        }

        subscription.copy(
            readPermissions = type.serializedReadPermissions(auth)
        )
    }
}