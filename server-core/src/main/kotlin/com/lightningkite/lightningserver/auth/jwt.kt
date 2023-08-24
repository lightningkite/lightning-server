package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.generalSettings
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.getContextualDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.util.*

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
