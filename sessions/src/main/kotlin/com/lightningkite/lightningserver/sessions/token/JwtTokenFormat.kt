package com.lightningkite.lightningserver.sessions.token

import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.services.database.HasId
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Access tokens as signed JWTs.
 *
 * Claims: `iat` is when the token was minted ([Authentication.tokenIssuedAt]) and `siat` is when the subject
 * authenticated ([Authentication.sessionIssuedAt]). Both are whole seconds.
 *
 * The token's `prn` header is the principal's name, and [read] returns `null` for a token whose `prn` is another
 * principal's, so one format can serve several principals.
 */
public class JwtTokenFormat(
    public val hasher: RuntimeDeferred<Signer> = secretBasis.signer("jwt"),
    public override val expiration: Duration = 5.minutes,
    public val issuer: Runtime<String> = generalSettings.map { it.publicUrl },
    public val audience: Runtime<String> = generalSettings.map { it.publicUrl },
) : TokenFormat {

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> create(
        principal: PrincipalType<SUBJECT, ID>,
        auth: Authentication<SUBJECT>,
    ): String {
        val now = now()
        return hasher.await().signJwt(
            principal.name,
            JwtClaims(
                iss = issuer(),
                sid = auth.sessionId,
                siat = auth.sessionIssuedAt.epochSeconds,
                sub = server.internalSerialization.json.encodeToString(principal.idSerializer, auth.id),
                aud = audience(),
                exp = now.plus(expiration).epochSeconds,
                iat = now.epochSeconds,
                nbf = now.epochSeconds,
                scope = auth.scopes.joinToString(" "),
                cache = server.internalSerialization.json.encodeToString(principal.cacheSerializer(), auth.cache)
            )
        )
    }

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> read(
        principal: PrincipalType<SUBJECT, ID>,
        value: String,
    ): Authentication<SUBJECT>? {
        val claims = hasher.await().verifyJwt(value, principal.name, audience(), issuer()) ?: return null

        return Authentication(
            principalType = principal,
            id = server.internalSerialization.json.decodeFromString(principal.idSerializer, claims.sub),
            sessionId = claims.sid,
            sessionIssuedAt = Instant.fromEpochSeconds(claims.siat),
            tokenIssuedAt = Instant.fromEpochSeconds(claims.iat),
            expiration = Instant.fromEpochSeconds(claims.exp),
            scopes = claims.scope.split(' ').mapTo(HashSet(), ::GrantedScope),
            cache = claims.cache?.let { server.internalSerialization.json.decodeFromString(principal.cacheSerializer(), it) }
        )
    }


    private var withDefaults: Json? = null

    context(server: ServerRuntime)
    private fun withDefaults(): Json {
        withDefaults?.let { return it }

        val json = Json(server.internalSerialization.json) { encodeDefaults = true; explicitNulls = false }

        withDefaults = json
        return json
    }

    context(server: ServerRuntime)
    private suspend fun Signer.signJwt(principalName: String, claims: JwtClaims): String = buildString {
        val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

        append(
            encoder.encode(
                withDefaults().encodeToString(JwtHeader(alg = name, prn = principalName)).encodeToByteArray()
            )
        )
        append('.')
        append(
            encoder.encode(
                withDefaults().encodeToString(claims).encodeToByteArray()
            )
        )
        val soFar = this.toString()
        val signature = encoder.encode(sign(soFar.encodeToByteArray()))
        append('.')
        append(signature)
    }

    context(server: ServerRuntime)
    private suspend fun Signer.verifyJwt(
        token: String,
        principalName: String,
        requiredAudience: String? = null,
        requiredIssuer: String? = null,
    ): JwtClaims? {

        val parts = token.split('.')
        if (parts.size != 3) return null  // It's not a JWT, so we'll ignore it.

        val decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

        val header: JwtHeader = try {
            server.internalSerialization.json.decodeFromString(decoder.decode(parts[0]).toString(Charsets.UTF_8))
        } catch (_: Exception) {
            // It's not a JWT, so we'll ignore it.
            return null
        }

        // Does not belong to this Jwt Formatter, return
        if (header.prn != principalName) return null

        if (header.alg != name) {
            throw JwtSignatureException("Algorithm mismatch: expected $name, got ${header.alg}")
        }

        val signature = try {
            decoder.decode(parts[2])
        } catch (e: IllegalArgumentException) {
            throw JwtFormatException("Malformed JWT")
        }

        if (!verify(token.substringBeforeLast('.').toByteArray(), signature))
            throw JwtSignatureException("JWT Signature is incorrect.")

        val claims = try {
            server.internalSerialization.json.decodeFromString<JwtClaims>(decoder.decode(parts[1]).decodeToString())
        } catch (e: IllegalArgumentException) {
            throw JwtFormatException("Malformed JWT")
        }

        requiredAudience?.let { if (claims.aud != it) throw JwtException("Invalid JWT") }
        requiredIssuer?.let { if (claims.iss != it) throw JwtException("Invalid JWT") }

        if (now() > Instant.fromEpochSeconds(claims.exp)) throw JwtExpiredException("JWT has expired.")
        if (claims.nbf?.let { now() < Instant.fromEpochSeconds(it) } == true) throw JwtException("Token not valid yet")

        return claims
    }
}
private fun <SUBJECT : HasId<*>> PrincipalType<SUBJECT, *>.cacheSerializer() =
    SerializableCache.Serializer(Authentication.serializer(subjectSerializer))
