package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.http.Request
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.reflect.KType
import kotlin.reflect.typeOf

data class JwtAuthenticationMethod(
    override val priority: Int = 0,
    override val fromStringInRequest: Authentication.FromStringInRequest,
    val audience: String? = null,
    val hasher: ()->SecureHasher
) : Authentication.Method<JwtClaims> {
    override val type: AuthType = AuthType<JwtClaims>()
    override val subjectType: Authentication.SubjectType? get() = null

    override suspend fun tryGet(on: Request): Authentication.Auth<JwtClaims>? {
        return fromStringInRequest.getString(on)?.let { token ->
            val claims: JwtClaims = hasher().verifyJwt(token, audience) ?: return@let null
            Authentication.Auth(
                value = claims,
                recentlyProven = claims.iat > System.currentTimeMillis() - 5 * 60 * 1000,
                scopes = claims.scope?.splitToSequence(' ')?.toSet()
            )
        }
    }

    fun sign(jwtClaims: JwtClaims) = hasher().also { println(it) }.signJwt(jwtClaims)
}