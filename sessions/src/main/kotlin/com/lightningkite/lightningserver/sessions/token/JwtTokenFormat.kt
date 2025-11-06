package com.lightningkite.lightningserver.sessions.token

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.GrantedScope
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.id
import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.definition.map
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.encryption.sign
import com.lightningkite.lightningserver.encryption.verify
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.services.database.HasId
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

public class JwtTokenFormat(
    public val hasher: RuntimeDeferred<Signer> = secretBasis.signer("jwt"),
    public val expiration: Duration = 5.minutes,
    public val issuer: Runtime<String> = generalSettings.map { it.publicUrl },
    public val audience: Runtime<String> = generalSettings.map { it.publicUrl }
) : TokenFormat {

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> create(
        principal: PrincipalType<SUBJECT, ID>,
        auth: Authentication<SUBJECT>
    ): String =
        hasher.await().signJwt(
            JwtClaims(
                iss = issuer(),
                sid = auth.sessionId,
                sub = "${principal.name}|${server.internalSerialization.json.encodeToString(principal.idSerializer, auth.id)}",
                aud = audience(),
                exp = now().plus(expiration).epochSeconds,
                iat = auth.issuedAt.epochSeconds,
                nbf = now().epochSeconds,
                scope = auth.scopes.joinToString(" "),
                thp = null, // TODO: Third parties
                cache = server.internalSerialization.json.encodeToString(auth.cache)
            )
        )

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> read(
        principal: PrincipalType<SUBJECT, ID>,
        value: String
    ): Authentication<SUBJECT>? {
        val prefix = "${principal.name}|"
        val claims = hasher.await().verifyJwt(value, audience()) ?: return null

        val rawSub = claims.sub!!
        val sub = if (rawSub.startsWith(prefix)) rawSub.removePrefix(prefix) else return null

        return Authentication(
            principalType = principal,
            id = server.internalSerialization.json.decodeFromString(principal.idSerializer, sub),
            sessionId = claims.sid,
            issuedAt = Instant.fromEpochSeconds(claims.iat),
            expiration = Instant.fromEpochSeconds(claims.exp),
            scopes = claims.scope!!.split(' ').mapTo(HashSet(), ::GrantedScope),
            cache = claims.cache?.let { server.internalSerialization.json.decodeFromString<SerializableCache>(it) }
        )
    }


    context(server: ServerRuntime)
    private suspend fun Signer.signJwt(claims: JwtClaims): String = buildString {
        val withDefaults = Json(server.internalSerialization.json) { encodeDefaults = true; explicitNulls = false }
        val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

        append(
            encoder.encode(
                withDefaults.encodeToString(JwtHeader(alg = name)).encodeToByteArray()
            )
        )
        append('.')
        append(
            encoder.encode(
                withDefaults.encodeToString(claims).encodeToByteArray()
            )
        )
        val soFar = this.toString()
        val signature = encoder.encode(sign(soFar.encodeToByteArray()))
        append('.')
        append(signature)
    }

    context(server: ServerRuntime)
    private suspend fun Signer.verifyJwt(token: String, requiredAudience: String? = null): JwtClaims? {
        val decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

        val parts = token.split('.')

        if (parts.size != 3) return null  // It's not a JWT, so we'll ignore it.

        val signature = decoder.decode(parts[2])

        val header: JwtHeader = server.internalSerialization.json.decodeFromString(decoder.decode(parts[0]).toString(Charsets.UTF_8))

        // Prevent algorithm confusion attacks by validating the algorithm matches what we expect
        if (header.alg != name) {
            throw JwtSignatureException("Algorithm mismatch: expected $name, got ${header.alg}")
        }

        val claims: JwtClaims = server.internalSerialization.json.decodeFromString(decoder.decode(parts[1]).toString(Charsets.UTF_8))

        requiredAudience?.let { if (claims.aud != it) return null }  // It's for someone else.  Ignore it.

        if (now() > Instant.fromEpochSeconds(claims.exp)) throw TokenException("JWT has expired.")
        if (claims.nbf?.let { now() < Instant.fromEpochSeconds(it) } == true) throw TokenException("Token not valid yet")

        if (!verify(token.substringBeforeLast('.').toByteArray(), signature))
            throw JwtSignatureException("JWT Signature is incorrect.")

        return claims
    }
}