package com.lightningkite.lightningserver.sessions.token

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.auth.Authentication
import kotlinx.serialization.Serializable


@Serializable
public data class JwtHeader(
    val typ: String = "JWT",
    val alg: String,
    val prn: String,
)

@Serializable
public data class JwtClaims(
    val iss: String? = null,
    val sub: String,
    val aud: String? = null,
    val exp: Long,
    val nbf: Long? = null,
    val iat: Long, // The token issue time
    val scope: String,
    val sid: String? = null,
    val siat: Long, // The Session issue time.
    /** The cache as JWTs wrote it before KotlinWireFormat: entries in OldKotlinBytesFormat.  Read only. */
    val cache: String? = null,
    /** The cache, entries in KotlinWireFormat. */
    val wireCache: String? = null,
)

/** For an authentication decoded from a token written before KotlinWireFormat. */
@OptIn(InternalLightningServerApi::class)
internal fun Authentication<*>.markCachesAsOldFormat() {
    cache.markEntriesAsOldFormat()
    fromMasquerade?.markCachesAsOldFormat()
}

public open class TokenException(message: String, override val cause: Throwable? = null) : Exception(message)
public open class JwtException(message: String) : TokenException(message)
public open class JwtFormatException(message: String) : JwtException(message)
public open class JwtSignatureException(message: String) : JwtException(message)
public open class JwtExpiredException(message: String) : JwtException(message)