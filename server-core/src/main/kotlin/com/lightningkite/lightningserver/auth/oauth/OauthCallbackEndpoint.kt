package com.lightningkite.lightningserver.auth.oauth

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

class OauthCallbackEndpoint<STATE>(
    path: ServerPath,
    val stateSerializer: KSerializer<STATE>,
    val oauthProviderInfo: OauthProviderInfo,
    val credentials: () -> OauthProviderCredentials,
    val defaultScope: String = oauthProviderInfo.scopeForProfile,
    val defaultAccessType: OauthAccessType = OauthAccessType.online,
    val onError: suspend (OauthCode) -> HttpResponse = {
        throw Exception("Got Oauth error from ${oauthProviderInfo.niceName}: ${it}")
    },
    val onAccess: suspend (OauthResponse, STATE) -> HttpResponse
) {
    fun loginUrl(
        state: STATE,
        scope: String = defaultScope,
        accessType: OauthAccessType = defaultAccessType,
    ) = oauthProviderInfo.loginUrl(
        credentials = credentials,
        callback = callback,
        scope = scope,
        state = Serialization.json.encodeToString(stateSerializer, state),
        accessType = accessType
    )

    val callback: HttpEndpoint = when (oauthProviderInfo.mode) {
        OauthResponseMode.form_post -> {
            val endpoint = path.post
            endpoint.handler { request ->
                val code = request.body!!.parse<OauthCode>()
                code.error?.let { onError(code) }
                val response = oauthProviderInfo.accessToken(credentials, endpoint, code)
                onAccess(response, Serialization.json.decodeFromString(stateSerializer, code.state!!))
            }
        }

        OauthResponseMode.query -> {
            val endpoint = path.get
            endpoint.handler { request ->
                val code = request.queryParameters<OauthCode>()
                code.error?.let { onError(code) }
                val response = oauthProviderInfo.accessToken(credentials, endpoint, code)
                onAccess(response, Serialization.json.decodeFromString(stateSerializer, code.state!!))
            }
        }
    }

    suspend fun accessToken(refreshToken: String): OauthResponse = oauthProviderInfo.accessToken(credentials, refreshToken)
}

inline fun <reified STATE> ServerPath.oauthCallback(
    oauthProviderInfo: OauthProviderInfo,
    noinline credentials: () -> OauthProviderCredentials,
    defaultScope: String = oauthProviderInfo.scopeForProfile,
    defaultAccessType: OauthAccessType = OauthAccessType.online,
    noinline onError: suspend (OauthCode) -> HttpResponse = {
        throw Exception("Got Oauth error from ${oauthProviderInfo.niceName}: ${it}")
    },
    noinline onAccess: suspend (OauthResponse, STATE) -> HttpResponse
) = OauthCallbackEndpoint(
    stateSerializer = Serialization.module.serializer<STATE>(),
    path = this,
    oauthProviderInfo = oauthProviderInfo,
    credentials = credentials,
    defaultScope = defaultScope,
    defaultAccessType = defaultAccessType,
    onError = onError,
    onAccess = onAccess,
)