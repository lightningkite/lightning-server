package com.lightningkite.lightningserver.sessions.openid

import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.sign
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.sessions.token.JwtHeader
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration

/**
 * JWT Issuer for OpenID Connect ID Tokens
 *
 * Creates and signs ID tokens (JWTs) according to OpenID Connect specifications.
 * Supports RS256 (RSA with SHA-256) signing, which is the required minimum algorithm
 * for OpenID Connect providers.
 *
 * @property signer The signing key (must be RS256 for OpenID Connect compliance)
 * @property issuer The issuer identifier (typically the server's public URL)
 * @property defaultExpiration Default expiration duration for ID tokens
 */
public class JwtIssuer(
    private val signer: Signer,
    private val issuer: String,
    private val defaultExpiration: Duration = kotlin.time.Duration.parse("1h"),
) {
    /**
     * Creates a signed ID Token (JWT)
     *
     * @param claims The ID token claims
     * @return A signed JWT string
     */
    context(server: ServerRuntime)
    @OptIn(ExperimentalEncodingApi::class)
    public suspend fun createIdToken(claims: IdTokenClaims): String {
        val json = Json(server.internalSerialization.json) {
            encodeDefaults = true
            explicitNulls = false
        }
        val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

        return buildString {
            // Header
            append(
                encoder.encode(
                    json.encodeToString(
                        JwtHeader.serializer(),
                        JwtHeader(typ = "JWT", alg = signer.name)
                    ).encodeToByteArray()
                )
            )
            append('.')

            // Payload (claims)
            append(
                encoder.encode(
                    json.encodeToString(IdTokenClaims.serializer(), claims).encodeToByteArray()
                )
            )

            // Signature
            val payload = this.toString()
            val signature = encoder.encode(signer.sign(payload.encodeToByteArray()))
            append('.')
            append(signature)
        }
    }

    /**
     * Helper to build ID token claims with automatic iss, iat, exp
     *
     * @param sub Subject identifier
     * @param aud Audience (client ID)
     * @param nonce Nonce from authorization request
     * @param authTime When user authenticated
     * @param additionalClaims Additional user claims
     * @return ID token claims ready to be signed
     */
    context(server: ServerRuntime)
    public fun buildClaims(
        sub: String,
        aud: String,
        nonce: String? = null,
        authTime: Long? = null,
        additionalClaims: IdTokenClaims.() -> IdTokenClaims = { this },
    ): IdTokenClaims {
        val now = now().epochSeconds
        return IdTokenClaims(
            iss = issuer,
            sub = sub,
            aud = aud,
            exp = now + defaultExpiration.inWholeSeconds,
            iat = now,
            auth_time = authTime,
            nonce = nonce,
        ).additionalClaims()
    }
}
