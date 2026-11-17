package com.lightningkite.lightningserver.notifications.events

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.notifications.query
import com.lightningkite.lightningserver.notifications.events.EventRegistry.Companion.events
import com.lightningkite.lightningserver.notifications.withPermissions
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.database.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/**
 * Provides REST endpoints for querying registered event types.
 *
 * Creates a POST endpoint that accepts [Query] objects to filter and retrieve
 * event types based on permissions and query conditions.
 *
 * @param AUTH The authentication type
 * @property eventSerializer Serializer for the application's event type
 * @property auth Authentication requirement for the endpoint
 * @property permissions Function to determine read permissions for event types
 */
public class EventEndpoints<E : EventTypeLike, AUTH : HasId<*>?>(
    public val eventSerializer: KSerializer<E>,
    public val auth: AuthRequirement<AUTH>,
    public val permissions: suspend context(ServerRuntime) AuthAccess<AUTH>.() -> ModelPermissions<E>,
) : ServerBuilder() {
    private fun <T> Sequence<T>.sortedWithNullable(comparator: Comparator<T>?): Sequence<T> =
        if (comparator == null) this else sortedWith(comparator)

    /**
     * Endpoint to query registered event types.
     *
     * Accepts a [Query] with filtering, sorting, and pagination options.
     * Results are filtered by read permissions before being returned.
     */
    public val queryEventTypes: ApiHttpHandler<PathSpec0, AUTH, Query<E>, List<E>> =
        path.post bind explicitApiHttpHandler(
            summary = "Query Event Types",
            description = "Queries for registered event types",
            auth = auth,
            inputType = Query.serializer(eventSerializer),
            outputType = ListSerializer(eventSerializer),
            implementation = { query: Query<E> ->
                serverRuntime.server.events.values
                    .asSequence()
                    .map { it.type as E }
                    .withPermissions(permissions(this))
                    .query(query)
                    .toList()
            }
        )
}