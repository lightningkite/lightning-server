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
    /**
     * Optional lookup for the PKCE `code_verifier` associated with the decoded state.
     *
     * If returning non-null, the verifier is sent in the token exchange. Implementations
     * typically pull from a server-side store (cache) populated when [loginUrl] was called.
     * The verifier MUST NEVER round-trip through the IdP — that's the whole point of PKCE.
     */
    public val pkceVerifierLookup: (suspend context(ServerRuntime) (STATE) -> String?)? = null,
    public val onAccess: suspend context(ServerRuntime) (OauthResponse, STATE) -> HttpResponse,
) : ServerBuilder() {

    context(runtime: ServerRuntime)
    public suspend fun handle(code: OauthCode): HttpResponse {
        code.error?.let { onError(code) }
        val decodedState = runtime.externalSerialization.json.decodeFromString(stateSerializer, code.state!!)
        val verifier = pkceVerifierLookup?.let { it(decodedState) }
        val response = oauthProviderInfo.accessToken(
            credentials = credentials,
            redirectUri = callback.location.path.resolved().fullUrl(),
            oauth = code,
            codeVerifier = verifier,
        )
        return onAccess(response, decodedState)
    }

    context(runtime: ServerRuntime)
    public suspend fun loginUrl(
        state: STATE,
        scope: String = defaultScope,
        accessType: OauthAccessType = defaultAccessType,
        loginHint: String? = null,
        nonce: String? = null,
        codeChallenge: String? = null,
    ): String = oauthProviderInfo.loginUrl(
        credentials = credentials,
        redirectUri = callback.location.path.resolved().fullUrl(),
        scope = scope,
        state = runtime.externalSerialization.json.encodeToString(stateSerializer, state),
        accessType = accessType,
        loginHint = loginHint,
        prompt = OauthPromptType.select_account,
        nonce = nonce,
        codeChallenge = codeChallenge,
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

