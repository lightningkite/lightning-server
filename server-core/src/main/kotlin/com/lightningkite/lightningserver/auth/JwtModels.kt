package com.lightningkite.lightningserver.auth

import kotlinx.serialization.Serializable

@Serializable
data class JwtHeader(val typ: String = "JWT", val alg: String = "HS256")

@Serializable
data class JwtClaims(
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
)

open class JwtException(message: String) : Exception(message)
open class JwtFormatException(message: String) : JwtException(message)
open class JwtSignatureException(message: String) : JwtException(message)
open class JwtExpiredException(message: String) : JwtException(message)
