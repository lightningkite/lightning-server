package com.lightningkite.lightningserver.sessions.openid

import com.lightningkite.lightningserver.ExperimentalOauthProviderApi

/**
 * A protected resource that [OpenIdProviderEndpoints] issues tokens for, such as an MCP server.
 *
 * Its identifier (RFC 8707) is the server's public URL followed by [path].  A token issued for it carries only
 * [scopes], so whatever requires those scopes accepts it and nothing else does.
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
