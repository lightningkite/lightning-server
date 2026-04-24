package com.lightningkite.lightningserver.sessions.proofs.oauth

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.serialization.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

public class OauthCallbackEndpoint<STATE>(
    path: PathSpec,
    public val stateSerializer: KSerializer<STATE>,
    public val oauthProviderInfo: OauthProviderInfo,
    public val credentials: () -> OauthProviderCredentials,
    public val defaultScope: String = oauthProviderInfo.scopeForProfile,
    public val defaultAccessType: OauthAccessType = OauthAccessType.online,
    public val onError: suspend context(ServerRuntime) (OauthCode) -> HttpResponse = {
        throw Exception("Got Oauth error from ${oauthProviderInfo.niceName}: ${it}")
    },
    public val onAccess: suspend context(ServerRuntime) (OauthResponse, STATE) -> HttpResponse,
) : ServerBuilder() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    context(runtime: ServerRuntime)
    public fun loginUrl(
        state: STATE,
        scope: String = defaultScope,
        accessType: OauthAccessType = defaultAccessType,
        loginHint: String? = null,
    ): String = oauthProviderInfo.loginUrl(
        credentials = credentials,
        callback = callback,
        scope = scope,
        state = json.encodeToString(stateSerializer, state),
        accessType = accessType,
        loginHint = loginHint,
    )

    public val callback: HttpHandler<*> = when (oauthProviderInfo.mode) {
        OauthResponseMode.form_post -> {
            path.post bind HttpHandler { request ->
                val code = request.body!!.parse<OauthCode>(OauthCode.serializer())
                code.error?.let { onError(code) }
                val response = oauthProviderInfo.accessToken(credentials, path, code)
                onAccess(response, json.decodeFromString(stateSerializer, code.state!!))
            }
        }

        OauthResponseMode.query -> {
            path.get bind HttpHandler { request ->
                val code = request.queryParameters<OauthCode>(OauthCode.serializer())
                code.error?.let { onError(code) }
                val response = oauthProviderInfo.accessToken(credentials, path, code)
                onAccess(response, json.decodeFromString(stateSerializer, code.state!!))
            }
        }
    }

    context(runtime: ServerRuntime)
    public suspend fun accessToken(refreshToken: String): OauthResponse =
        oauthProviderInfo.accessToken(credentials, refreshToken)
}

context(builder: ServerBuilder)
public inline fun <reified STATE> HttpEndpoint<PathSpec0>.oauthCallback(
    oauthProviderInfo: OauthProviderInfo,
    noinline credentials: () -> OauthProviderCredentials,
    defaultScope: String = oauthProviderInfo.scopeForProfile,
    defaultAccessType: OauthAccessType = OauthAccessType.online,
    noinline onError: suspend context(ServerRuntime) (OauthCode) -> HttpResponse = {
        throw Exception("Got Oauth error from ${oauthProviderInfo.niceName}: ${it}")
    },
    noinline onAccess: suspend context(ServerRuntime) (OauthResponse, STATE) -> HttpResponse,
): OauthCallbackEndpoint<STATE> = OauthCallbackEndpoint(
    path = path,
    stateSerializer = serializerOrContextual<STATE>(),
    oauthProviderInfo = oauthProviderInfo,
    credentials = credentials,
    defaultScope = defaultScope,
    defaultAccessType = defaultAccessType,
    onError = onError,
    onAccess = onAccess,
)