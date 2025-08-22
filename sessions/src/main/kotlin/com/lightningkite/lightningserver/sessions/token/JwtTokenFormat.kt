package com.lightningkite.lightningserver.sessions.token

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.RequestPredicates
import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.data.set
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.encryption.SecureHasher
import com.lightningkite.lightningserver.encryption.hasher
import com.lightningkite.lightningserver.encryption.sign
import com.lightningkite.lightningserver.encryption.verify
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.sessions.Authentication
import com.lightningkite.lightningserver.sessions.Session
import com.lightningkite.lightningserver.sessions.sessionId
import com.lightningkite.services.database.HasId
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

public class JwtTokenFormat(
    public val hasher: RuntimeDeferred<SecureHasher.WithId> = secretBasis.hasher("jwt"),
    public val expiration: Duration = 5.minutes,
    public val issuerOverride: String? = null,
    public val audienceOverride: String? = null,
): TokenFormat {
    public val issuer: Runtime<String> = Runtime { issuerOverride ?: generalSettings().publicUrl }
    public val audience: Runtime<String> = Runtime { audienceOverride ?: generalSettings().publicUrl }

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> create(
        handler: PrincipalType<SUBJECT, ID>,
        auth: Authentication<SUBJECT, ID>
    ): String =
        hasher.await().signJwt(
            JwtClaims(
                iss = issuer(),
                sid = auth.sessionId,
                sub = "${handler.name}|${server.internalSerialization.json.encodeToString(handler.idSerializer, auth.id)}",
                aud = audience(),
                exp = now().plus(expiration).epochSeconds,
                iat = auth.issuedAt.epochSeconds,
                nbf = now().epochSeconds,
                scope = auth.limitTo?.scopes?.joinToString(" "),
                thp = null, // TODO: Third parties
                cache = server.internalSerialization.json.encodeToString(auth.cache)
            )
        )

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> read(
        handler: PrincipalType<SUBJECT, ID>,
        value: String
    ): Authentication<SUBJECT, ID>? {
        val prefix = "${handler.name}|"
        val claims = hasher.await().verifyJwt(value, audience()) ?: return null

        val rawSub = claims.sub!!
        val sub = if(rawSub.startsWith(prefix)) rawSub.removePrefix(prefix) else return null

        if (now() > Instant.fromEpochSeconds(claims.exp)) throw TokenException("Token has expired")
        if (claims.nbf?.let { now() < Instant.fromEpochSeconds(it) } == true) throw TokenException("Token not valid yet")

        return Authentication(
            principalType = handler,
            id = server.internalSerialization.json.decodeFromString(handler.idSerializer, sub),
            sessionId = claims.sid,
            issuedAt = Instant.fromEpochSeconds(claims.iat),
            limitTo = claims.scope?.let { RequestPredicates(scopes = it.split(' ').toSet()) },
            cache = claims.cache?.let { server.internalSerialization.json.decodeFromString<SerializableCache>(it) }
        )
    }


    context(server: ServerRuntime)
    private suspend fun SecureHasher.WithId.signJwt(claims: JwtClaims): String = buildString {
        val withDefaults = Json(server.internalSerialization.json) { encodeDefaults = true; explicitNulls = false }
        val encoder = Base64.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

        append(
            encoder.encode(
                withDefaults.encodeToString(JwtHeader(alg = id)).toByteArray()
            )
        )
        append('.')
        append(
            encoder.encode(
                withDefaults.encodeToString(claims).toByteArray()
            )
        )
        val soFar = this.toString()
        append('.')
        val signature = encoder.encode(sign(soFar.toByteArray()))
        append(signature)
    }

    context(server: ServerRuntime)
    private suspend fun SecureHasher.WithId.verifyJwt(token: String, requiredAudience: String? = null): JwtClaims? {
        val decoder = Base64.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

        val parts = token.split('.')

        if (parts.size != 3) return null  // It's not a JWT, so we'll ignore it.

        val signature = decoder.decode(parts[2])

        @Suppress("UNUSED_VARIABLE")
        val header: JwtHeader = server.internalSerialization.json.decodeFromString(decoder.decode(parts[0]).toString(Charsets.UTF_8))

        val claims: JwtClaims = server.internalSerialization.json.decodeFromString(decoder.decode(parts[1]).toString(Charsets.UTF_8))

        requiredAudience?.let { if (claims.aud != it) return null }  // It's for someone else.  Ignore it.

        if (System.currentTimeMillis() / 1000L > claims.exp) throw JwtExpiredException("JWT has expired.")

        if (!verify(token.substringBeforeLast('.').toByteArray(), signature))
            throw JwtSignatureException("JWT Signature is incorrect.")

        return claims
    }
}