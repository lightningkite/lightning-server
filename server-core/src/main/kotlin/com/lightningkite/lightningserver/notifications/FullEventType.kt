package com.lightningkite.lightningserver.notifications

import com.lightningkite.UUID
import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.db.ModelInfo
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.typed.AuthAccessor
import kotlinx.datetime.*

class FullEventType<USER : HasId<UID>, UID : Comparable<UID>, T : HasId<ID>, ID : Comparable<ID>, CONTENT : NotificationContent>(
    val type: EventType,
    val info: ModelInfo<USER, T, ID>,
    registry: Registry<USER, UID, CONTENT>,
    val defaultSubscription: suspend (USER)->EventSubscription.Info<T>? = { null },
    val contentGenerator: suspend (T) -> (USER) -> CONTENT
) {
    val name get() = type.name
    val tags = type.tags

    val conditionSerializer = Condition.serializer(info.serialization.serializer)

    init {
        registry.register(this)
    }

    override fun toString(): String = type.name

    class Registry<USER : HasId<UID>, UID : Comparable<UID>, CONTENT : NotificationContent> {
        class EventTypeRegistrationException(
            val name: String,
            override val message: String,
            override val cause: Throwable? = null
        ) : Exception()

        private val registry = HashMap<String, FullEventType<USER, UID, HasId<*>, *, CONTENT>>()
        val registered: Collection<FullEventType<USER, UID, HasId<*>, *, CONTENT>> get() = registry.values

        @Suppress("UNCHECKED_CAST")
        fun <T:HasId<ID>, ID:Comparable<ID>> register(type: FullEventType<USER, UID, T, ID, CONTENT>) {
            if (type.name in registry.keys) throw EventTypeRegistrationException(type.name, "Event type '${type.name}' is not unique and has been registered before")
            registry[type.name] = type as FullEventType<USER, UID, HasId<*>, *, CONTENT>
        }

        fun byTags(predicate: (tags: Set<String>) -> Boolean) = registered.filter { predicate(it.tags) }.map { it.type }

        operator fun get(name: String) = registry[name] ?: throw EventTypeRegistrationException(name, "Event type \"$name\" is not registered")
        operator fun get(eventType: EventType) = get(eventType.name)
    }

    object SubscriptionBehavior {
        const val REPLACE = "DEFAULT SUBSCRIPTION BEHAVIOR - REPLACE ON USER CHANGES"
        const val IGNORE_USER_CHANGES = "DEFAULT SUBSCRIPTION BEHAVIOR - IGNORE USER CHANGES"
    }
}

suspend fun <USER : HasId<UID>, UID : Comparable<UID>> EventSubscription.Info<HasId<*>>.toEventSubscription(
    type: FullEventType<USER, UID, HasId<*>, *, out NotificationContent>,
    user: USER
): EventSubscription<UID> {
    val permissions = type.info.permissions(AuthAccessor.test(user))    // NOTE: This gives full auth access for the user, which should be fine in most cases for notifications

    return EventSubscription(
        _id = UserEventType(user._id, type.type),
        filterHashes = emptySet(),  // TODO: No idea what these are supposed to be
        requestedFilter = Serialization.json.encodeToString(type.conditionSerializer, filter),
        readPermissions = Serialization.json.encodeToString(type.conditionSerializer, permissions.read),
        frequency = frequency,
        email = email,
        sms = sms,
        push = push
    )
}

// Event with full target object and type info, used at runtime to create notifications
data class FullEvent<USER : HasId<UID>, UID : Comparable<UID>, T : HasId<ID>, ID : Comparable<ID>, CONTENT : NotificationContent>(
    override val _id: UUID = UUID.random(),
    val time: Instant,
    val type: FullEventType<USER, UID, T, ID, CONTENT>,
    val target: T
): HasId<UUID> {
    val serialized = Event(
        _id = _id,
        time = time,
        type = type.type,
        target = Serialization.json.encodeToString(type.info.serialization.idSerializer, target._id)
    )
}

private inline fun <K, V, R : Any?> Map<K, V>.mapNotNullValues(transform: (Map.Entry<K, V>)->R?): Map<K, R> {
    val destination = HashMap<K, R>()
    for (entry in entries) {
        transform(entry)?.let { destination[entry.key] = it }
    }
    return destination
}


suspend fun <USER : HasId<UID>, UID : Comparable<UID>> NotificationEndpoints<USER, UID, *>.updateDefaultSubscriptions(
    users: List<USER>,
    typeFilter: (FullEventType<USER, UID, HasId<*>, *, *>)->Boolean,
) {
    val types = eventRegistry.registered.filter { typeFilter(it) }

    val defaults = types
        .flatMap { type ->
            users.mapNotNull { user ->
                type.defaultSubscription(user)?.toEventSubscription(type, user)
            }
        }
        .associateBy { it._id }

    val existing = subscriptions
        .collection()
        .getMany(defaults.keys)
        .associateBy { it._id }

    val missing = defaults.filter { it.key !in existing.keys }.values

    val update = defaults
        .mapNotNullValues { (id, default) ->
            val current = existing[id] ?: return@mapNotNullValues null
            default
                .copy(
                    frequency = current.frequency,
                    email = current.email,
                    sms = current.sms,
                    push = current.push
                )
                .takeUnless { it == current }
        }

    subscriptions
        .collection()
        .deleteMany(
            subscriptions.condition { it._id inside update.keys }
        )

    subscriptions.collection().insert(missing + update.values)
}