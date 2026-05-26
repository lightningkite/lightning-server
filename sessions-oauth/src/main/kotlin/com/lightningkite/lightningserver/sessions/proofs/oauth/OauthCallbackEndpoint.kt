package com.lightningkite.lightningserver.sessions.proofs.oauth

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.fullUrl
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.sessions.proofs.oauth.path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

public class OauthCallbackEndpoint<STATE>(
    path: PathSpec0,
    public val stateSerializer: KSerializer<STATE>,
    public val oauthProviderInfo: OauthProviderInfo,
    public val credentials: Runtime<OauthProviderCredentials>,
    public val defaultScope: String = oauthProviderInfo.scopeForProfile,
    public val defaultAccessType: OauthAccessType = OauthAccessType.online,
    public val onError: suspend context(ServerRuntime) (OauthCode) -> HttpResponse = {
        throw Exception("Got Oauth error from ${oauthProviderInfo.niceName}: ${it}")
    },
    public val onAccess: suspend context(ServerRuntime) (OauthResponse, STATE) -> HttpResponse,
) : ServerBuilder() {

    context(runtime: ServerRuntime)
    public suspend fun handle(code: OauthCode): HttpResponse {
        code.error?.let { onError(code) }
        val response = oauthProviderInfo.accessToken(credentials, callback.location.path.resolved().fullUrl(), code)
        return onAccess(response, runtime.externalSerialization.json.decodeFromString(stateSerializer, code.state!!))
    }

    context(runtime: ServerRuntime)
    public fun loginUrl(
        state: STATE,
        scope: String = defaultScope,
        accessType: OauthAccessType = defaultAccessType,
        loginHint: String? = null,
    ): String = oauthProviderInfo.loginUrl(
        credentials = credentials,
        redirectUri = callback.location.path.resolved().fullUrl(),
        scope = scope,
        state = runtime.externalSerialization.json.encodeToString(stateSerializer, state),
        accessType = accessType,
        loginHint = loginHint,
        prompt = OauthPromptType.select_account
    )

    public val callback: HttpHandler<PathSpec0> = when (oauthProviderInfo.mode) {
        OauthResponseMode.form_post -> {
            path.post bind HttpHandler { request ->
                handle(request.body!!.parse(OauthCode.serializer()))
            }
        }

        OauthResponseMode.query -> {
            path.get bind HttpHandler { request ->
                handle(request.queryParameters(OauthCode.serializer()))
            }
        }
    }

    context(runtime: ServerRuntime)
    public suspend fun accessToken(refreshToken: String): OauthResponse =
        oauthProviderInfo.accessToken(credentials, refreshToken)
}

