package com.lightningkite.lightningserver.auth.token

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.auth.id
import com.lightningkite.lightningserver.encryption.JwtClaims
import com.lightningkite.lightningserver.encryption.SecureHasher
import com.lightningkite.lightningserver.encryption.signJwt
import com.lightningkite.lightningserver.encryption.verifyJwt
import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.decodeUnwrappingString
import com.lightningkite.lightningserver.serialization.encodeUnwrappingString
import com.lightningkite.lightningserver.settings.generalSettings
import java.time.Duration
import java.time.Instant

class JwtTokenFormat(
    val hasher: () -> SecureHasher,
    val issuerOverride: String? = null,
    val audienceOverride: String? = null,
): TokenFormat {
    val issuer: String get() = issuerOverride ?: generalSettings().publicUrl
    val audience: String get() = audienceOverride ?: generalSettings().publicUrl
    override fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> create(
        handler: Authentication.SubjectHandler<SUBJECT, ID>,
        auth: RequestAuth<SUBJECT>
    ): String {
        @Suppress("UNCHECKED_CAST")
        return hasher().signJwt(
            JwtClaims(
                iss = issuer,
                sub = "${handler.name}|${Serialization.json.encodeUnwrappingString<ID>(handler.idSerializer, auth.id)}",
                aud = audience,
                exp = Instant.now().plus(Duration.ofMinutes(5)).epochSecond,
                iat = auth.issuedAt.epochSecond,
                nbf = Instant.now().epochSecond,
                scope = auth.scopes?.joinToString(" "),
                thp = auth.thirdParty,
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
        if(Instant.now() > Instant.ofEpochSecond(claims.exp)) throw UnauthorizedException("Token has expired")
        if(claims.nbf?.let { Instant.now() < Instant.ofEpochSecond(it) } == true) throw UnauthorizedException("Token not valid yet")
        if(claims.nbf?.let { Instant.now() < Instant.ofEpochSecond(it) } == true) throw UnauthorizedException("Token not valid yet")
        return RequestAuth(
            subject = handler,
            rawId = Serialization.json.decodeUnwrappingString(handler.idSerializer, sub),
            issuedAt = Instant.ofEpochSecond(claims.iat),
            scopes = claims.scope?.split(' ')?.toSet(),
            cachedRaw = mapOf(),
            thirdParty = claims.thp
        )
    }

}