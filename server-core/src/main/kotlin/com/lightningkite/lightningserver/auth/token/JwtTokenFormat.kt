package com.lightningkite.lightningserver.auth.token

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.decodeUnwrappingString
import com.lightningkite.lightningserver.serialization.encodeUnwrappingString
import com.lightningkite.lightningserver.settings.generalSettings
import kotlinx.datetime.Clock
import com.lightningkite.now
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.time.Duration
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.minutes

class JwtTokenFormat(
    val hasher: () -> SecureHasher,
    val expiration: Duration = 5.minutes,
    val issuerOverride: String? = null,
    val audienceOverride: String? = null,
): TokenFormat {
    val issuer: String get() = issuerOverride ?: generalSettings().publicUrl
    val audience: String get() = audienceOverride ?: generalSettings().publicUrl
    override fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> create(
        handler: Authentication.SubjectHandler<SUBJECT, ID>,
        auth: RequestAuth<SUBJECT>
    ): String {
        return hasher().signJwt(
            JwtClaims(
                iss = issuer,
                sid = auth.sessionId,
                sub = "${handler.name}|${Serialization.json.encodeUnwrappingString(handler.idSerializer, auth.id)}",
                aud = audience,
                exp = now().plus(expiration).epochSeconds,
                iat = auth.issuedAt.epochSeconds,
                nbf = now().epochSeconds,
                scope = auth.scopes.joinToString(" "),
                thp = auth.thirdParty,
                cache = Serialization.json.encodeToString(auth.cacheKeyMap())
            )
        )
    }

    override fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> read(
        handler: Authentication.SubjectHandler<SUBJECT, ID>,
        value: String
    ): RequestAuth<SUBJECT>? {
        val prefix = "${handler.name}|"
        val claims = hasher().verifyJwt(value, audience) ?: return null
        val rawSub = claims.sub!!
        val sub = if(rawSub.startsWith(prefix)) rawSub.removePrefix(prefix) else return null
        if(now() > Instant.fromEpochSeconds(claims.exp)) throw TokenException("Token has expired")
        if(claims.nbf?.let { now() < Instant.fromEpochSeconds(it) } == true) throw TokenException("Token not valid yet")
        return RequestAuth(
            subject = handler,
            rawId = Serialization.json.decodeUnwrappingString(handler.idSerializer, sub),
            issuedAt = Instant.fromEpochSeconds(claims.iat),
            scopes = claims.scope?.split(' ')?.toSet() ?: setOf("*"),
            thirdParty = claims.thp,
            sessionId = claims.sid
        ).also {
            claims.cache?.let { c ->
                it.cacheKeyMap(Serialization.json.decodeFromString(CacheKeyMapSerializer, c))
            }
        }
    }

}