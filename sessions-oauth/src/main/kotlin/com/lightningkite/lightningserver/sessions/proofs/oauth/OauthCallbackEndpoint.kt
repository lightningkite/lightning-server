package com.lightningkite.lightningserver.sessions.proofs.oauth

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.serverRuntime
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.services.database.contextualSerializerIfHandled
import kotlinx.serialization.KSerializer

public class OauthCallbackEndpoint<STATE>(
    path: PathSpec,
    public val stateSerializer: KSerializer<STATE>,
    public val oauthProviderInfo: OauthProviderInfo,
    public val credentials: () -> OauthProviderCredentials,
    public val defaultScope: String = oauthProviderInfo.scopeForProfile,
    public val defaultAccessType: OauthAccessType = OauthAccessType.online,
    public val onError: suspend (OauthCode) -> HttpResponse = {
        throw Exception("Got Oauth error from ${oauthProviderInfo.niceName}: ${it}")
    },
    public val onAccess: suspend (OauthResponse, STATE) -> HttpResponse
): ServerBuilder() {

    public fun loginUrl(
        state: STATE,
        scope: String = defaultScope,
        accessType: OauthAccessType = defaultAccessType,
        loginHint: String? = null,
    ):String = oauthProviderInfo.loginUrl(
        credentials = credentials,
        callback = callback,
        scope = scope,
        state = externalSerialization.json.encodeToString(stateSerializer, state),
        accessType = accessType,
        loginHint = loginHint,
    )

    context(_: ServerRuntime)
    public val callback: HttpHandler<PathSpec> get() = when (oauthProviderInfo.mode) {
        OauthResponseMode.form_post -> {
            endpoint bind HttpHandler { request ->
                val code = request.body!!.parse<OauthCode>()
                code.error?.let { onError(code) }
                val response = oauthProviderInfo.accessToken(credentials, endpoint, code)
                onAccess(response, Serialization.json.decodeFromString(stateSerializer, code.state!!))
            }
        }

        OauthResponseMode.query -> {
            val endpoint = path.get
            endpoint bind HttpHandler { request ->
                val code = request.queryParameters<OauthCode>()
                code.error?.let { onError(code) }
                val response = oauthProviderInfo.accessToken(credentials, endpoint, code)
                onAccess(response, Serialization.json.decodeFromString(stateSerializer, code.state!!))
            }
        }
    }

    public suspend fun accessToken(refreshToken: String): OauthResponse = oauthProviderInfo.accessToken(credentials, refreshToken)
}

context(builder: ServerBuilder)
public inline fun <reified STATE> HttpEndpoint<PathSpec0>.oauthCallback(
    oauthProviderInfo: OauthProviderInfo,
    noinline credentials: () -> OauthProviderCredentials,
    defaultScope: String = oauthProviderInfo.scopeForProfile,
    defaultAccessType: OauthAccessType = OauthAccessType.online,
    noinline onError: suspend (OauthCode) -> HttpResponse = {
        throw Exception("Got Oauth error from ${oauthProviderInfo.niceName}: ${it}")
    },
    noinline onAccess: suspend (OauthResponse, STATE) -> HttpResponse
):OauthCallbackEndpoint<STATE> = OauthCallbackEndpoint(
    path = path,
    stateSerializer = builder.externalSerialization.contextualSerializerIfHandled<STATE>(),
    oauthProviderInfo = oauthProviderInfo,
    credentials = credentials,
    defaultScope = defaultScope,
    defaultAccessType = defaultAccessType,
    onError = onError,
    onAccess = onAccess,
)