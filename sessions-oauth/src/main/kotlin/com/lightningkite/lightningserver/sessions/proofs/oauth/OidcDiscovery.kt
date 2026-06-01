package com.lightningkite.lightningserver.sessions.proofs.oauth

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.services.http.client
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Fetches and caches an OpenID Connect provider's discovery document
 * (`.well-known/openid-configuration`).
 *
 * One instance per IdP. The document is fetched lazily on first use and cached for [cacheFor].
 * The IdP's published `issuer` is validated against the discovery URL's origin to defend against
 * a tampered or substituted discovery document pointing at a different IdP's token endpoint.
 *
 * @param url Full URL to the `.well-known/openid-configuration` document, served over HTTPS.
 * @param cacheFor Duration to reuse the cached document before refetching.
 */
public class OidcDiscovery(
    public val url: String,
    public val cacheFor: Duration = 24.hours,
) {
    private val mutex = Mutex()
    private var cached: OidcDiscoveryDocument? = null
    private var cachedAt: Instant? = null

    init {
        require(url.startsWith("https://")) { "OIDC discovery URL must be HTTPS: $url" }
    }

    context(runtime: ServerRuntime)
    public suspend fun document(): OidcDiscoveryDocument = mutex.withLock {
        val current = cached
        val at = cachedAt
        if (current != null && at != null && now() < at + cacheFor) return@withLock current

        val fetched = client.get(url).body<OidcDiscoveryDocument>()
        val expectedOrigin = url.substringBefore("/.well-known/")
        require(fetched.issuer == expectedOrigin || fetched.issuer.startsWith(expectedOrigin)) {
            "OIDC discovery issuer '${fetched.issuer}' does not match discovery URL origin '$expectedOrigin'"
        }
        cached = fetched
        cachedAt = now()
        fetched
    }

    /**
     * Returns a [Jwks] instance whose URL is sourced from this discovery document.
     * Cached separately by URL so multiple [Jwks] are not created per process per IdP.
     */
    context(runtime: ServerRuntime)
    public suspend fun jwks(cacheFor: Duration = 24.hours): Jwks =
        jwksByUrl.withLock {
            val uri = document().jwks_uri
            jwksByUrlMap.getOrPut(uri) { Jwks(uri, cacheFor) }
        }

    private val jwksByUrl = Mutex()
    private val jwksByUrlMap = HashMap<String, Jwks>()
}

/**
 * OIDC provider metadata as published at `.well-known/openid-configuration`.
 *
 * Only fields used by this implementation are typed. Additional fields are silently ignored.
 *
 * @property issuer Issuer URL. MUST be exactly this string in `iss` claims of issued tokens.
 * @property authorization_endpoint URL the user is redirected to for login.
 * @property token_endpoint URL for exchanging authorization codes (and refresh tokens) for tokens.
 * @property userinfo_endpoint Optional URL for fetching user profile after token exchange.
 * @property jwks_uri URL serving the IdP's signing keys (JWKS document).
 * @property id_token_signing_alg_values_supported Signing algorithms the IdP advertises.
 * @property scopes_supported Scopes advertised by the IdP.
 * @property code_challenge_methods_supported PKCE code-challenge methods advertised.
 */
@Serializable
public data class OidcDiscoveryDocument(
    val issuer: String,
    val authorization_endpoint: String,
    val token_endpoint: String,
    val jwks_uri: String,
    val userinfo_endpoint: String? = null,
    val id_token_signing_alg_values_supported: List<String> = emptyList(),
    val scopes_supported: List<String> = emptyList(),
    val code_challenge_methods_supported: List<String> = emptyList(),
)
