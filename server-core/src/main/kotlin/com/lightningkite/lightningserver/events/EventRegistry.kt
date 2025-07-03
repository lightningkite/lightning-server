package com.lightningkite.lightningserver.events

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.ModelPermissions
import com.lightningkite.lightningdb.Query
import com.lightningkite.lightningdb.comparator
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.authOptions
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.notifications.EventType
import com.lightningkite.lightningserver.typed.AuthAccessor
import com.lightningkite.lightningserver.typed.api
import kotlinx.serialization.builtins.ListSerializer

class EventRegistry<USER : HasId<*>?>(
    path: ServerPath,
    val authOptions: AuthOptions<USER>,
    val permissions: suspend (AuthAccessor<USER>) -> ModelPermissions<EventType> = { ModelPermissions.allowAll() }
) : ServerPathGroup(path) {
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

    operator fun get(eventType: EventType): TypedEventType<USER, *, *> {
        val name = eventType.name
        return registry[name] ?: throw EventTypeRegistrationException(name, "Event type \"$name\" is not registered")
    }

    private fun <T> List<T>.sortedWithNullable(comparator: Comparator<T>?): List<T> = if (comparator == null) this else sortedWith(comparator)

    val queryEventTypes = path("events").post.api(
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

inline fun <reified USER : HasId<*>?> EventRegistry(
    path: ServerPath,
    noinline permissions: suspend (AuthAccessor<USER>) -> ModelPermissions<EventType> = { ModelPermissions.allowAll() }
) = EventRegistry(path, authOptions<USER>(), permissions)