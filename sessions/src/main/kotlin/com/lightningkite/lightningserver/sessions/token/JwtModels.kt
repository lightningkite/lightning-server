package com.lightningkite.lightningserver.sessions.token

import kotlinx.serialization.Serializable

@Serializable
public data class JwtHeader(val typ: String = "JWT", val alg: String = "HS256")

@Serializable
public data class JwtClaims(
    val iss: String? = null,
    val sub: String? = null,
    val aud: String? = null,
    val exp: Long,
    val nbf: Long? = null,
    val iat: Long = System.currentTimeMillis() / 1000L,
    val jti: String? = null,
    val userId: String? = null,
    val scope: String? = null,
    val thp: String? = null,
    val sid: String? = null,
    val cache: String? = null,
    val email: String? = null,
    val emailVerified: Boolean? = null
)

public open class TokenException(message: String, override val cause: Throwable? = null) : Exception(message)
public open class JwtException(message: String) : TokenException(message)
public open class JwtFormatException(message: String) : JwtException(message)
public open class JwtSignatureException(message: String) : JwtException(message)
public open class JwtExpiredException(message: String) : JwtException(message)