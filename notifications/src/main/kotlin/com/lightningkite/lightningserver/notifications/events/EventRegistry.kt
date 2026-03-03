package com.lightningkite.lightningserver.notifications.events

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.definition.builder.MapRegistry
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.post
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

/**
 * Registry for managing typed event types in the notification system.
 *
 * This is a lightweight wrapper around a [MapRegistry] that ensures type-safe storage
 * and retrieval of event types. Event types are automatically registered during construction
 * of [TypedEventType] instances.
 *
 * @param USER The user type (nullable for public events)
 */
public class EventRegistry<USER : HasId<*>?>(
    private val registry: MapRegistry<String, TypedEventType<USER, *, *>> = MapRegistry()
) : Map<String, TypedEventType<USER, *, *>> by registry {
    /**
     * Registers an event type with this registry.
     *
     * Called automatically by [TypedEventType] constructor.
     */
    public fun register(eventType: TypedEventType<USER, *, *>) {
        registry.register(eventType.name, eventType)
    }
}

/**
 * Provides REST endpoints for querying registered event types.
 *
 * Creates a POST endpoint that accepts [Query] objects to filter and retrieve
 * event types based on permissions and query conditions.
 *
 * @param AUTH The authentication type
 * @property registry The event registry to query
 * @property auth Authentication requirement for the endpoint
 * @property permissions Function to determine read permissions for event types
 */
public class EventEndpoints<AUTH : HasId<*>?>(
    private val registry: EventRegistry<*>,
    public val auth: AuthRequirement<AUTH>,
    public val permissions: suspend context(ServerRuntime) AuthAccess<AUTH>.() -> ModelPermissions<EventType>
) : ServerBuilder() {
    private fun <T> List<T>.sortedWithNullable(comparator: Comparator<T>?): List<T> = if (comparator == null) this else sortedWith(comparator)

    /**
     * Endpoint to query registered event types.
     *
     * Accepts a [Query] with filtering, sorting, and pagination options.
     * Results are filtered by read permissions before being returned.
     */
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
                    .map { it.untyped }
                    .filter { permissions.read(it) && query.condition(it) }
                    .map(permissions::mask)
                    .sortedWithNullable(query.orderBy.comparator)
                    .drop(query.skip)
                    .take(query.limit)
            }
        )
}
