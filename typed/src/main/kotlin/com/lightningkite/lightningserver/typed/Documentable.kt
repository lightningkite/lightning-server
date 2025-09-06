package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.commonPrefix
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketTopic
import kotlinx.serialization.KSerializer

public interface Documentable {
    public val auth: AuthRequirement<*>
    public val inputType: KSerializer<*>
    public val outputType: KSerializer<*>
    public val summary: String
    public val description: String

    public val belongsToInterface: OldInterfaceInfo?

    public class OldInterfaceInfo(public val fullyQualifiedName: String, public val typeArguments: List<KSerializer<*>>)
}

internal fun ServerDefinition.location(documentable: Documentable): PathSpec = when (documentable) {
    is HttpHandler<*> -> location(documentable).path
    is WebSocketHandler<*, *> -> location(documentable)
    is WebSocketTopic<*, *> -> location(documentable)
    is Task<*> -> location(documentable)
    is StartupTask -> location(documentable)
    is ScheduledTask -> location(documentable)
    else -> throw IllegalStateException()
}

internal val Documentable.functionName: String
    get() = summary
        .replace(Regex("""[^0-9a-zA-Z]+(?<following>.)?""")) { match ->
            match.groups["following"]?.value?.uppercase() ?: ""
        }
        .replaceFirstChar { it.lowercase() }

internal val ServerDefinition.locationedApiHttpHandlers: List<Locationed<HttpEndpoint<PathSpec>, ApiHttpHandler<*, *, *, *>>>
    get() = endpoints.entries.flatMap {
    it.value.http.entries
        .filter { it.value is ApiHttpHandler<*, *, *, *> }
        .map { h -> Locationed(HttpEndpoint(it.key, h.key), h.value as ApiHttpHandler<*, *, *, *>) }
}
    .sortedBy { it.location.run { "$method $path"} }
internal val ServerDefinition.locationedApiWebsocketHandlers: List<Locationed<PathSpec, ApiWebsocketHandler<*, *, *, *, *>>>
    get() = endpoints.entries.mapNotNull {
    (it.value.websocket as? ApiWebsocketHandler<*, *, *, *, *>)
        ?.let { h -> Locationed(it.key, h) }
}
    .sortedBy { it.location.toString() }

internal val ServerDefinition.apiHttpHandlers: List<ApiHttpHandler<*, *, *, *>>
    get() = endpoints.values.flatMap { it.http.values.filterIsInstance<ApiHttpHandler<*, *, *, *>>() }
internal val ServerDefinition.apiWebsocketHandlers: List<ApiWebsocketHandler<*, *, *, *, *>>
    get() = endpoints.values.mapNotNull { it.websocket as? ApiWebsocketHandler<*, *, *, *, *> }

internal val ServerDefinition.interfaces: List<Locationed<PathSpec, Documentable.OldInterfaceInfo>>
    get() {
        val e = endpoints
            .values
            .asSequence()
            .flatMap { it.http.values.asSequence() }
            .filterIsInstance<ApiHttpHandler<*, *, *, *>>()
        val w = endpoints
            .values
            .asSequence()
            .mapNotNull { it.websocket }
            .filterIsInstance<ApiWebsocketHandler<*, *, *, *, *>>()
        return (w + e)
            .filter { it.belongsToInterface != null }
            .groupBy { it.belongsToInterface!! }
            .map {
                Locationed(it.value.mapNotNull { documentable ->
                    when (documentable) {
                        is ApiHttpHandler<*, *, *, *> -> location(documentable).path
                        is ApiWebsocketHandler<*, *, *, *, *> -> location(documentable)
                        else -> null
                    }
                }.reduce { a, b -> a commonPrefix b }, it.key)
            }
    }