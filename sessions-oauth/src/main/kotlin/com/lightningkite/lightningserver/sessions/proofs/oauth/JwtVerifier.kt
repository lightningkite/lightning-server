package com.lightningkite.lightningserver.sessions.proofs.oauth

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.encryption.verify
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.sessions.token.JwtExpiredException
import com.lightningkite.lightningserver.sessions.token.JwtFormatException
import com.lightningkite.lightningserver.sessions.token.JwtSignatureException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies OpenID Connect ID Token JWTs.
 *
 * Combines a [Jwks] key source with a strict, fail-closed validation pipeline that enforces:
 * - Signature against the IdP's published key matched by `kid`
 * - Algorithm is in [allowedAlgorithms] (defaults to RS256 only — guards against `alg: none`)
 * - `iss` matches [expectedIssuer]
 * - `aud` contains [expectedAudience]
 * - `exp` and `nbf` honored within [clockSkew]
 * - `nonce` matches [expectedNonce] when supplied (callers MUST pass this for replay safety)
 *
 * @param expectedIssuer The IdP's issuer URL, as obtained from its discovery document.
 * @param expectedAudience Our `client_id` as registered with the IdP.
 * @param jwks The JWKS key source for this IdP.
 * @param clockSkew Tolerance applied to `exp`/`nbf` checks.
 * @param allowedAlgorithms JWT signing algorithms accepted by this verifier. Keep RS256-only
 *   unless you have specific reason to expand it.
 */
public class JwtVerifier(
    public val expectedIssuer: String,
    public val expectedAudience: String,
    public val jwks: Jwks,
    public val clockSkew: Duration = 60.seconds,
    public val allowedAlgorithms: Set<String> = setOf("RS256"),
) {

    /**
     * Verifies the given JWT and returns its parsed claims.
     *
     * @param idToken The JWT string from an OIDC token response.
     * @param expectedNonce If non-null, the JWT's `nonce` claim must match this value exactly.
     *   Callers initiating an OIDC login MUST pass the nonce they originally sent.
     */
    @OptIn(ExperimentalEncodingApi::class)
    context(runtime: ServerRuntime)
    public suspend fun verify(idToken: String, expectedNonce: String? = null): OidcIdTokenClaims {
        val decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
        val parts = idToken.split('.')
        if (parts.size != 3) throw JwtFormatException("Expected 3 JWT parts, got ${parts.size}")

        val headerBytes = decoder.decode(parts[0])
        val payloadBytes = decoder.decode(parts[1])
        val signature = decoder.decode(parts[2])

        val header = jsonLenient.decodeFromString<JwtHeader>(headerBytes.decodeToString())

        if (header.alg !in allowedAlgorithms) {
            throw JwtSignatureException("Algorithm '${header.alg}' not in allowed set $allowedAlgorithms")
        }
        val kid = header.kid ?: throw JwtSignatureException("JWT header missing 'kid'")

        val claims = jsonLenient.decodeFromString<OidcIdTokenClaims>(payloadBytes.decodeToString())

        if (claims.iss != expectedIssuer) {
            throw BadRequestException("Issuer '${claims.iss}' does not match expected '$expectedIssuer'")
        }
        if (expectedAudience !in claims.aud) {
            throw BadRequestException("Audience '$expectedAudience' not present in aud=${claims.aud}")
        }
        val nowInstant = now()
        if (nowInstant.epochSeconds > claims.exp + clockSkew.inWholeSeconds) {
            throw JwtExpiredException("Token expired at ${claims.exp}")
        }
        claims.nbf?.let { nbf ->
            if (nowInstant.epochSeconds + clockSkew.inWholeSeconds < nbf) {
                throw BadRequestException("Token not yet valid (nbf=$nbf)")
            }
        }
        if (expectedNonce != null) {
            if (claims.nonce != expectedNonce) {
                throw BadRequestException("Nonce mismatch in ID token")
            }
        }

        val signer = jwks.signer(kid)
        val signingInput = (parts[0] + "." + parts[1]).encodeToByteArray()
        val verified = try {
            signer.verify(signingInput, signature)
        } catch (e: Throwable) {
            throw JwtSignatureException("JWT signature verification failed: ${e.message ?: e::class.simpleName}")
        }
        if (!verified) throw JwtSignatureException("JWT signature verification failed")

        return claims
    }

    public companion object {
        private val jsonLenient: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}

@Serializable
internal data class JwtHeader(
    val alg: String,
    val kid: String? = null,
    val typ: String? = null,
)
