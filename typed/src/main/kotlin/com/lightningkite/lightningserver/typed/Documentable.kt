package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.pathing.PathSpec
import kotlinx.serialization.KSerializer

public interface Documentable {
    public val auth: AuthRequirement<*>
    public val inputType: KSerializer<*>
    public val outputType: KSerializer<*>
    public val summary: String
    public val description: String

    public val belongsToInterface: InterfaceInfo?

    public data class InterfaceInfo(val fullyQualifiedName: String, val typeArguments: List<KSerializer<*>>)
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

internal val ServerDefinition.interfaces
    get() = endpoints
        .values
        .asSequence()
        // TODO: include websockets
        .filterIsInstance<ApiHttpHandler<*, *, *, *>>()
        .mapNotNull { it.belongsToInterface }
        .distinct()