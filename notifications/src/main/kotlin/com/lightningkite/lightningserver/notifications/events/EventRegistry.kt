package com.lightningkite.lightningserver.notifications.events

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.definition.builder.MapRegistry
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.notifications.EventType
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.lightningserver.typed.explicitApiHttpHandler
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.ModelPermissions
import com.lightningkite.services.database.Query
import com.lightningkite.services.database.comparator
import kotlinx.serialization.builtins.ListSerializer

@JvmInline
public value class EventRegistry<USER : HasId<*>?>(
    private val registry: MapRegistry<String, TypedEventType<USER, *, *>> = MapRegistry()
) : Map<String, TypedEventType<USER, *, *>> by registry {
    public fun register(eventType: TypedEventType<USER, *, *>) {
        registry.register(eventType.name, eventType)
    }
}

public class EventEndpoints<AUTH : HasId<*>?>(
    private val registry: EventRegistry<*>,
    public val auth: AuthRequirement<AUTH>,
    public val permissions: suspend context(ServerRuntime) AuthAccess<AUTH>.() -> ModelPermissions<EventType>
) : ServerBuilder() {
    private fun <T> List<T>.sortedWithNullable(comparator: Comparator<T>?): List<T> = if (comparator == null) this else sortedWith(comparator)

    public val queryEventTypes: ApiHttpHandler<PathSpec0, AUTH, Query<EventType>, List<EventType>> =
        path.post bind explicitApiHttpHandler(
            summary = "Query Event Types",
            description = "Queries for registered event types",
            auth = auth,
            inputType = Query.serializer(EventType.serializer()),
            outputType = ListSerializer(EventType.serializer()),
            implementation = { query: Query<EventType> ->
                val permissions = permissions(this)

                registry.values
                    .map { it.type }
                    .filter { permissions.read(it) && query.condition(it) }
                    .map(permissions::mask)
                    .sortedWithNullable(query.orderBy.comparator)
                    .drop(query.skip)
                    .take(query.limit)
            }
        )
}
