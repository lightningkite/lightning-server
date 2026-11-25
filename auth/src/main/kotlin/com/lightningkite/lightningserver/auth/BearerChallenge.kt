package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.ExperimentalOauthProviderApi
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.pathing.RawWebSocketPath
import com.lightningkite.lightningserver.runtime.ServerRuntime

@OptIn(ExperimentalOauthProviderApi::class)
context(server: ServerRuntime)
internal fun protectedResourceAt(request: Request<*>): ProtectedResource? {
    val resources = server.server.protectedResources
    if (resources.isEmpty()) return null
    // The path as requested: resolving it would throw for a request that matches no route.
    val segments = when (val path = request.path) {
        is RawHttpEndpoint<*> -> path.pathSegments
        is RawWebSocketPath<*> -> path.pathSegments
        else -> throw IllegalStateException("Unexpected request path type ${path::class}")
    }
    return resources.find { it.path == "/$segments" }
}

/**
 * The `WWW-Authenticate` header of an RFC 6750 bearer challenge.  At a [ProtectedResource] it also carries
 * `resource_metadata` (RFC 9728) and, unless [scopes] names others, the resource's scopes.
 */
@OptIn(ExperimentalOauthProviderApi::class)
context(server: ServerRuntime)
internal fun bearerChallenge(request: Request<*>?, error: String?, scopes: Set<RequiredScope>?): HttpHeaders {
    val resource = request?.let { protectedResourceAt(it) }
    val metadata = resource?.let { generalSettings().publicUrl + "/.well-known/oauth-protected-resource" + it.path }
    val scope = scopes?.joinToString(" ") { it.asString } ?: resource?.scopes?.joinToString(" ")
    val parameters = listOfNotNull(
        error?.let { "error=\"$it\"" },
        metadata?.let { "resource_metadata=\"$it\"" },
        scope?.let { "scope=\"$it\"" },
    )
    val value = if (parameters.isEmpty()) "Bearer" else "Bearer " + parameters.joinToString(", ")
    return HttpHeaders { add(HttpHeader.WWWAuthenticate, value) }
}
