package com.lightningkite.lightningserver.notifications.split

import com.lightningkite.UUID
import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.ModelPermissions
import com.lightningkite.lightningdb.Query
import com.lightningkite.lightningdb.comparator
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.anyAuth
import com.lightningkite.lightningserver.auth.authOptions
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.ModelInfo
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.notifications.Event
import com.lightningkite.lightningserver.notifications.EventType
import com.lightningkite.lightningserver.notifications.FullEventType
import com.lightningkite.lightningserver.notifications.FullEventType.Registry.EventTypeRegistrationException
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.typed.AuthAccessor
import com.lightningkite.lightningserver.typed.api
import com.lightningkite.now
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer

class EventRegistry<USER : HasId<*>?>(
    val path: ServerPath,
    val authOptions: AuthOptions<USER>,
    val permissions: suspend (AuthAccessor<USER>) -> ModelPermissions<EventType> = { ModelPermissions.allowAll() }
) {
    class EventTypeRegistrationException(
        val name: String,
        override val message: String,
        override val cause: Throwable? = null
    ) : Exception()

    private val registry = HashMap<String, TypedEventType<USER, *, *>>()
    val registered: Collection<TypedEventType<USER, *, *>> get() = registry.values

    fun <T:HasId<ID>, ID:Comparable<ID>> register(type: TypedEventType<USER, T, ID>) {
        registry[type.name]?.let {
            if (it.info != type.info) throw EventTypeRegistrationException(type.name, "Event type '${type.name}' is not unique and has been registered before")
        }
        registry[type.name] = type
    }

    fun byTags(predicate: (tags: Set<String>) -> Boolean) = registered.filter { predicate(it.tags) }.map { it.type }

    operator fun get(name: String) = registry[name] ?: throw EventTypeRegistrationException(name, "Event type \"$name\" is not registered")
    operator fun get(eventType: EventType) = get(eventType.name)



    private fun <T> List<T>.sortedWithNullable(comparator: Comparator<T>?): List<T> = if (comparator == null) this else sortedWith(comparator)

    val queryEventTypes = path.path("events").post.api(
        summary = "Query Event Types",
        description = "Queries for registered event types",
        authOptions = authOptions,
        inputType = Query.serializer(EventType.serializer()),
        outputType = ListSerializer(EventType.serializer()),
        implementation = { query: Query<EventType> ->
            val permissions = permissions(this)

            registered
                .map { it.type }
                .filter { permissions.read(it) && query.condition(it) }
                .map(permissions::mask)
                .sortedWithNullable(query.orderBy.comparator)
                .drop(query.skip)
                .take(query.limit)
        }
    )
}

inline fun <reified USER : HasId<*>?> EventRegisitry(
    path: ServerPath,
    authOptions: AuthOptions<USER> = authOptions<USER>(),
    noinline permissions: suspend (AuthAccessor<USER>) -> ModelPermissions<EventType> = { ModelPermissions.allowAll() }
) = EventRegistry(path, authOptions, permissions)


class TypedEventType<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    val name: String,
    val tags: Set<String>,
    val info: ModelInfo<USER, T, ID>,
    registry: EventRegistry<USER>
) {
    val type = EventType(name, tags)

    override fun toString(): String = name

    val conditionSerializer = Condition.serializer(info.serialization.serializer)

    init {
        registry.register(this)
    }
}

data class TypedEvent<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    override val _id: UUID = UUID.random(),
    val time: Instant = now(),
    val type: TypedEventType<USER, T, ID>,
    val subject: T
): HasId<UUID> {
    fun toEvent() = Event(
        _id = _id,
        timestamp = time,
        type = type.type,
        subject = Serialization.json.encodeToString(type.info.serialization.idSerializer, subject._id)
    )
}

interface EventHandler<USER : HasId<*>?> {
    suspend fun <T : HasId<ID>, ID : Comparable<ID>> handle(event: TypedEvent<USER, T, ID>)
}