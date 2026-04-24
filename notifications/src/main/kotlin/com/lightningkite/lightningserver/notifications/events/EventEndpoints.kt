package com.lightningkite.lightningserver.notifications.events

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.notifications.events.EventRegistry.Companion.events
import com.lightningkite.lightningserver.notifications.query
import com.lightningkite.lightningserver.notifications.withPermissions
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.database.*
import kotlinx.serialization.builtins.ListSerializer

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
    public val auth: AuthRequirement<AUTH>,
    public val permissions: suspend context(ServerRuntime) AuthAccess<AUTH>.() -> ModelPermissions<EventType>,
) : ServerBuilder() {
    private fun <T> Sequence<T>.sortedWithNullable(comparator: Comparator<T>?): Sequence<T> =
        if (comparator == null) this else sortedWith(comparator)

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
                serverRuntime.server.events.values
                    .asSequence()
                    .map { it.untyped }
                    .withPermissions(permissions(this))
                    .query(query)
                    .toList()
            }
        )
}