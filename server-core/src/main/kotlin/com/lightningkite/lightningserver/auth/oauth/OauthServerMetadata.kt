package com.lightningkite.lightningserver.auth.oauth

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.handler
import com.lightningkite.lightningserver.routes.fullUrl
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Represents the OAuth 2.0 Authorization Server Metadata as defined in RFC 8414.
 * This metadata is exposed at the .well-known/oauth-authorization-server endpoint.
 */
@Serializable
data class OauthServerMetadata(
    // REQUIRED fields
    val issuer: String,
    val authorization_endpoint: String,
    val token_endpoint: String,

    // RECOMMENDED fields
    val jwks_uri: String? = null,
    val registration_endpoint: String? = null,
    val scopes_supported: List<String>? = null,
    val response_types_supported: List<String> = listOf("code"),
    val response_modes_supported: List<String> = listOf("query", "form_post"),
    val grant_types_supported: List<String> = listOf(OauthGrantTypes.authorizationCode, OauthGrantTypes.refreshToken),
    val token_endpoint_auth_methods_supported: List<String> = listOf("client_secret_basic", "client_secret_post"),

    // OPTIONAL fields
    val service_documentation: String? = null,
    val ui_locales_supported: List<String>? = null,
    val op_policy_uri: String? = null,
    val op_tos_uri: String? = null,
    val revocation_endpoint: String? = null,
    val revocation_endpoint_auth_methods_supported: List<String>? = null,
    val introspection_endpoint: String? = null,
    val introspection_endpoint_auth_methods_supported: List<String>? = null,
    val code_challenge_methods_supported: List<String>? = null,

    // Additional fields can be included as extensions
    val additional_properties: JsonObject? = null
)

/**
 * Registers the OAuth 2.0 Authorization Server Metadata endpoint.
 * This endpoint is available at .well-known/oauth-authorization-server
 */
fun registerOauthServerMetadata(
    issuer: String,
    authorizationEndpoint: HttpEndpoint,
    tokenEndpoint: HttpEndpoint,
    supportedScopes: List<String>? = null,
    jwksUri: String? = null,
    registrationEndpoint: HttpEndpoint? = null,
    additionalMetadata: JsonObject? = null
): HttpEndpoint {
    val wellKnownPath = ServerPath.root.path(".well-known").path("oauth-authorization-server")

    return wellKnownPath.get.handler { _ ->
        HttpResponse.json(OauthServerMetadata(
            issuer = issuer,
            authorization_endpoint = authorizationEndpoint.path.fullUrl(),
            token_endpoint = tokenEndpoint.path.fullUrl(),
            jwks_uri = jwksUri,
            registration_endpoint = registrationEndpoint?.path?.fullUrl(),
            scopes_supported = supportedScopes,
            additional_properties = additionalMetadata,
        ))
    }
}