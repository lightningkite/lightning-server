@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.encryption

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import com.lightningkite.UUID

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
    val sid: UUID? = null,
    val cache: String? = null,
)

open class TokenException(message: String) : Exception(message)
open class JwtException(message: String) : TokenException(message)
open class JwtFormatException(message: String) : JwtException(message)
open class JwtSignatureException(message: String) : JwtException(message)
open class JwtExpiredException(message: String) : JwtException(message)
