package com.lightningkite.lightningserver.sessions.token

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
    val cache: String? = null,
)

public open class TokenException(message: String, override val cause: Throwable? = null) : Exception(message)
public open class JwtException(message: String) : TokenException(message)
public open class JwtFormatException(message: String) : JwtException(message)
public open class JwtSignatureException(message: String) : JwtException(message)
public open class JwtExpiredException(message: String) : JwtException(message)