package com.lightningkite.lightningserver.sessions.proofs.oauth

import com.lightningkite.lightningserver.BadRequestException
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
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.getAndRemove
import com.lightningkite.services.cache.set
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public class OauthCallbackEndpoint<STATE>(
    public val stateSerializer: KSerializer<STATE>,
    public val oauthProviderInfo: OauthProviderInfo,
    public val credentials: Runtime<OauthProviderCredentials>,
    /**
     * Holds the `state` marker and PKCE code verifier between the authorization redirect and the callback.
     *
     * In multi-instance or serverless deployments this must be a shared cache such as Redis, not the in-memory `"ram"` cache, since the callback can land on a different instance than the one that started the flow.
     */
    public val cache: Runtime<Cache>,
    public val defaultScope: String = oauthProviderInfo.scopeForProfile,
    public val defaultAccessType: OauthAccessType = OauthAccessType.online,
    /** Returns how long an in-progress flow stays in the cache, which must cover the user's time at the provider. */
    public val flowExpiration: Duration = 10.minutes,
    /**
     * Handles the provider redirecting back with an error, by default throwing.
     *
     * This runs before `state` is validated, so the [OauthCode] is attacker-controllable and none of its fields can be trusted.
     */
    public val onError: suspend context(ServerRuntime) (OauthCode) -> HttpResponse = {
        throw Exception("Got Oauth error from ${oauthProviderInfo.niceName}: $it")
    },
    public val onAccess: suspend context(ServerRuntime) (OauthResponse, STATE) -> HttpResponse,
) : ServerBuilder() {

    /**
     * Holds the data of one in-progress OAuth flow, keyed in the cache by the `state` nonce the provider echoes back.
     *
     * One cache lookup on the callback therefore validates the `state` and retrieves the PKCE verifier and the caller's state.
     */
    @Serializable
    internal data class FlowRecord(
        /** Holds the caller's `STATE` value, serialized with [stateSerializer]. */
        val state: String,
        /** Holds the PKCE code verifier, or `null` if the provider does not support PKCE. */
        val codeVerifier: String?,
    )

    internal fun flowKey(nonce: String): String = "oauth-flow-${oauthProviderInfo.identifierName}-$nonce"

    context(runtime: ServerRuntime)
    public suspend fun handle(code: OauthCode): HttpResponse {
        code.error?.let { return onError(code) }
        // CSRF protection (and PKCE): the `state` the provider echoed back must match a stored,
        // unconsumed flow we issued. getAndRemove enforces single use, so a replayed callback fails.
        // Limitation: the nonce is stored server-side but not bound to the initiating browser (this is
        // a cookie-less proof design). That still stops the classic forged-callback CSRF, but not
        // "login CSRF" where an attacker completes their own flow and hands the victim the resulting
        // callback URL. Bind the flow to a browser cookie if that threat is in scope.
        val nonce = code.state ?: throw BadRequestException("Missing OAuth state parameter.")
        val record = cache().getAndRemove<FlowRecord>(flowKey(nonce))
            ?: throw BadRequestException("Invalid, expired, or already-used OAuth state.")
        val response = oauthProviderInfo.accessToken(
            credentials,
            callback.location.path.resolved().fullUrl(),
            code,
            codeVerifier = record.codeVerifier,
        )
        return onAccess(response, runtime.externalSerialization.json.decodeFromString(stateSerializer, record.state))
    }

    context(runtime: ServerRuntime)
    public suspend fun loginUrl(
        state: STATE,
        scope: String = defaultScope,
        accessType: OauthAccessType = defaultAccessType,
        loginHint: String? = null,
    ): String {
        // The `state` we send to the provider is an opaque, single-use nonce (not the caller state),
        // so nothing sensitive leaks through the provider and callbacks can be validated for CSRF.
        val nonce = randomUrlToken()
        val codeVerifier = if (oauthProviderInfo.supportsPkce) generatePkceCodeVerifier() else null
        cache().set(
            flowKey(nonce),
            FlowRecord(
                state = runtime.externalSerialization.json.encodeToString(stateSerializer, state),
                codeVerifier = codeVerifier,
            ),
            flowExpiration,
        )
        return oauthProviderInfo.loginUrl(
            credentials = credentials,
            redirectUri = callback.location.path.resolved().fullUrl(),
            scope = scope,
            state = nonce,
            accessType = accessType,
            loginHint = loginHint,
            prompt = OauthPromptType.select_account,
            codeChallenge = codeVerifier?.let { pkceCodeChallengeS256(it) },
        )
    }

    public val callback: HttpHandler<PathSpec0> = when (oauthProviderInfo.mode) {
        OauthResponseMode.form_post -> {
            path.post bind HttpHandler { request ->
                handle(
                    request.body
                        ?.parse(OauthCode.serializer())
                        ?: throw BadRequestException()
                )
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
