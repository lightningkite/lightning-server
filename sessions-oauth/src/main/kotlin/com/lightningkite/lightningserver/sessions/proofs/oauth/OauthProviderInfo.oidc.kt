package com.lightningkite.lightningserver.sessions.proofs.oauth

import com.lightningkite.lightningserver.BadRequestException

/**
 * Builds an [OauthProviderInfo] for an OpenID Connect provider, sourcing endpoint URLs and signing
 * keys from the IdP's `.well-known/openid-configuration` document.
 *
 * Unlike the hand-written providers ([OauthProviderInfo.Companion.google] et al.) this factory:
 * - Verifies the IdP's `id_token` (a JWT) against keys published at the IdP's JWKS endpoint,
 *   rather than calling a userinfo endpoint. This is one fewer round trip and gives a
 *   cryptographically attested email.
 * - Refuses unverified emails by default. An IdP that does not include `email_verified: true`
 *   in its claims will be rejected. Some enterprise IdPs only ever issue verified emails — set
 *   [requireEmailVerified] to false ONLY for such providers, and ONLY after confirming their
 *   verification semantics.
 *
 * The discovery document is fetched lazily on first use and cached for 24h.
 *
 * @param niceName Human-readable name. Used in SDK type names and log messages.
 * @param discoveryUrl Absolute HTTPS URL to the IdP's `.well-known/openid-configuration`.
 * @param scopeForProfile Scopes to request. Must include `openid`. Defaults include `email` and
 *   `profile` since this provider's purpose is identity, not API access.
 * @param emailClaim Which claim of the verified ID token to read the user's email from.
 *   Most IdPs put it in `email`; some enterprise IdPs use `preferred_username` or `upn`.
 * @param requireEmailVerified If true (default), reject ID tokens whose `email_verified` claim
 *   is missing or false. Keep this true unless you have explicitly verified the IdP guarantees
 *   verified emails by some other mechanism.
 * @param mode SAML-style response mode. Most OIDC IdPs accept either; defaults to `query`.
 */
public fun OauthProviderInfo.Companion.fromOidcDiscovery(
    niceName: String,
    discoveryUrl: String,
    scopeForProfile: String = "openid email profile",
    emailClaim: String = "email",
    requireEmailVerified: Boolean = true,
    mode: OauthResponseMode = OauthResponseMode.query,
): OauthProviderInfo {
    val discovery = OidcDiscovery(discoveryUrl)
    return OauthProviderInfo(
        niceName = niceName,
        loginUrl = "",
        tokenUrl = "",
        mode = mode,
        scopeForProfile = scopeForProfile,
        resolveEndpoints = {
            val doc = discovery.document()
            OauthProviderInfo.Endpoints(
                loginUrl = doc.authorization_endpoint,
                tokenUrl = doc.token_endpoint,
            )
        },
        getProfile = { response, credentials ->
            val idToken = response.id_token
                ?: throw BadRequestException("$niceName did not return an id_token; is 'openid' in the scopes?")
            val clientId = credentials?.id
                ?: throw BadRequestException("OIDC verification requires the client credentials")

            val doc = discovery.document()
            val verifier = JwtVerifier(
                expectedIssuer = doc.issuer,
                expectedAudience = clientId,
                jwks = discovery.jwks(),
            )
            val claims = verifier.verify(idToken)

            if (requireEmailVerified && claims.email_verified != true) {
                throw BadRequestException("$niceName did not assert email_verified=true; refusing to trust email")
            }

            val email = when (emailClaim) {
                "email" -> claims.email
                "preferred_username" -> claims.preferred_username
                else -> throw BadRequestException("Unsupported emailClaim '$emailClaim'")
            } ?: throw BadRequestException("$niceName did not include claim '$emailClaim' in id_token")

            ExternalProfile(
                email = email,
                name = claims.name,
                image = claims.picture,
                username = claims.preferred_username,
            )
        }
    )
}
