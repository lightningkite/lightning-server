package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.ExperimentalOauthProviderApi
import com.lightningkite.lightningserver.UnauthorizedException
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.data.get
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.definition.ListRegistryExtension
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.builder.ListRegistry
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.getValue

/**
 * A protected resource (RFC 9728) that OAuth clients get tokens for, such as an MCP server.
 *
 * Its identifier (RFC 8707) is the server's public URL followed by [path].  A token issued for it carries only
 * [scopes], so whatever requires those scopes accepts it and nothing else does.
 *
 * Once registered in [protectedResources] (`OpenIdProviderEndpoints` registers its `resources`), requests to exactly
 * [path], not paths under it, accept only tokens whose [Authentication.audience] is its identifier, so the app's own
 * tokens, even root ones, are refused there with a 401 `invalid_token` (see [authentication]).  Its auth challenges
 * carry `resource_metadata`, pointing at `/.well-known/oauth-protected-resource` followed by [path], and `scope`,
 * which is how clients find where to log in.
 *
 * @param path Where the resource is served, starting with `/`, e.g. `/mcp`.
 * @param scopes The scopes its tokens may carry, e.g. `mcp`.  No two resources may share a scope.
 * @param name Shown to the user by clients that read the resource's metadata.
 */
@ExperimentalOauthProviderApi
public data class ProtectedResource(
    val path: String,
    val scopes: Set<String>,
    val name: String? = null,
) {
    init {
        require(path.startsWith("/")) { "Resource path must start with '/': $path" }
        require(scopes.isNotEmpty()) { "Resource $path needs at least one scope" }
    }
}

/**
 * This request's [Authentication], as [AuthRequirement.assert] checks it.
 *
 * At a registered [ProtectedResource], a token whose [Authentication.audience] isn't that resource is refused with a
 * 401 `invalid_token`.  Reading [Authentication.CacheKey] directly skips that check.
 */
@OptIn(ExperimentalOauthProviderApi::class)
context(server: ServerRuntime)
public suspend fun Request<*>.authentication(): Authentication<*>? {
    val auth = this[Authentication.CacheKey] ?: return null
    val resource = protectedResourceAt(this) ?: return auth
    if (auth.audience != generalSettings().publicUrl + resource.path) throw UnauthorizedException(
        message = "This token was not issued for ${resource.path}.",
        headers = bearerChallenge(this, "invalid_token", scopes = null),
    )
    return auth
}

@OptIn(ExperimentalOauthProviderApi::class)
private object ProtectedResources : ListRegistryExtension<ProtectedResource>

@ExperimentalOauthProviderApi
public val ServerBuilder.protectedResources: ListRegistry<ProtectedResource> by ProtectedResources

@ExperimentalOauthProviderApi
public val ServerDefinition.protectedResources: List<ProtectedResource> by ProtectedResources
