package com.lightningkite.lightningserver.auth.oauth

import com.lightningkite.lightningserver.auth.oauth.path
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.set
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.serialization.contextualSerializerIfHandled
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration.Companion.days

class OauthCallbackEndpoint<STATE>(
    val path: ServerPath,
    val stateSerializer: KSerializer<STATE>,
    val oauthProviderInfo: OauthProviderInfo,
    val credentials: () -> OauthProviderCredentials,
    val cache: () -> Cache,
    val defaultScope: String = oauthProviderInfo.scopeForProfile,
    val defaultAccessType: OauthAccessType = OauthAccessType.online,
    val onError: suspend (OauthCode) -> HttpResponse = {
        throw Exception("Got Oauth error from ${oauthProviderInfo.niceName}: ${it}")
    },
    val onAccess: suspend (OauthResponse, STATE) -> HttpResponse
) {
    @Serializable data class LoginInfo(
        val codeVerifier: String,
        val issuer: String? = null,
    )
    suspend fun loginUrl(
        state: STATE,
        scope: String = defaultScope,
        accessType: OauthAccessType = defaultAccessType,
        loginHint: String? = null,
    ): String {
        val codeVerifier = ByteArray(64).also {
            SecureRandom.getInstanceStrong().nextBytes(it)
        }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val codeChallenge = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.UTF_8))
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val stateString = Serialization.json.encodeToString(stateSerializer, state)

        // Store the code verifier for later use
        cache().set("oauth-callback-$path-$stateString", LoginInfo(codeVerifier, oauthProviderInfo.issuer), timeToLive = 1.days)

        return oauthProviderInfo.loginUrl(
            credentials = credentials,
            callback = callback,
            scope = scope,
            state = stateString,
            accessType = accessType,
            loginHint = loginHint,
            codeChallenge = codeChallenge,
            codeChallengeMethod = "S256",
        )
    }

    val callback: HttpEndpoint = when (oauthProviderInfo.mode) {
        OauthResponseMode.form_post -> {
            val endpoint = path.post
            endpoint.handler { request ->
                handle(request.body!!.parse<OauthCode>(), endpoint)
            }
        }

        OauthResponseMode.query -> {
            val endpoint = path.get
            endpoint.handler { request ->
                handle(request.queryParameters<OauthCode>(), endpoint)
            }
        }
    }

    private suspend fun handle(
        code: OauthCode,
        endpoint: HttpEndpoint
    ): HttpResponse {
        code.error?.let { onError(code) }
        val response = oauthProviderInfo.accessToken(credentials, endpoint, code, code.state?.let { stateString ->
            val key = "oauth-callback-$path-$stateString"
            val value = cache().get<LoginInfo>(key) ?: throw BadRequestException("No such state: $stateString.")
            if (value.issuer != null && value.issuer != code.iss) throw BadRequestException("Issuer mismatch in OAuth response.  Expected ${value.issuer}, got ${code.iss}")
            cache().remove(key)
            value.codeVerifier
        })
        return onAccess(response, Serialization.json.decodeFromString(stateSerializer, code.state!!))
    }

    suspend fun accessToken(refreshToken: String): OauthResponse = oauthProviderInfo.accessToken(credentials, refreshToken)
}

inline fun <reified STATE> ServerPath.oauthCallback(
    oauthProviderInfo: OauthProviderInfo,
    noinline credentials: () -> OauthProviderCredentials,
    noinline cache: () -> Cache,
    defaultScope: String = oauthProviderInfo.scopeForProfile,
    defaultAccessType: OauthAccessType = OauthAccessType.online,
    noinline onError: suspend (OauthCode) -> HttpResponse = {
        throw Exception("Got Oauth error from ${oauthProviderInfo.niceName}: ${it}")
    },
    noinline onAccess: suspend (OauthResponse, STATE) -> HttpResponse
) = OauthCallbackEndpoint(
    stateSerializer = Serialization.module.contextualSerializerIfHandled<STATE>(),
    path = this,
    cache = cache,
    oauthProviderInfo = oauthProviderInfo,
    credentials = credentials,
    defaultScope = defaultScope,
    defaultAccessType = defaultAccessType,
    onError = onError,
    onAccess = onAccess,
)