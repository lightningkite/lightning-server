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
     * Cache used to hold transient per-flow data (the CSRF `state` marker and the PKCE code verifier)
     * between the authorization redirect and the callback. Entries are single-use and short-lived.
     *
     * In multi-instance or serverless deployments this MUST be a shared cache (e.g. Redis, DynamoDB),
     * not the in-memory `"ram"` cache: the callback can land on a different instance than the one that
     * started the flow, and an unshared cache would fail to find the flow record, breaking login.
     */
    public val cache: Runtime<Cache>,
    public val defaultScope: String = oauthProviderInfo.scopeForProfile,
    public val defaultAccessType: OauthAccessType = OauthAccessType.online,
    /**
     * How long an in-progress OAuth flow may sit in the cache before its `state`/verifier expire.
     * Must comfortably cover the user's time at the provider (login, consent, MFA).
     */
    public val flowExpiration: Duration = 10.minutes,
    /**
     * Invoked when the provider redirects back with an error. NOTE: this runs before `state` is
     * validated, so the [OauthCode] passed here is unauthenticated and fully attacker-controllable —
     * do not trust its fields for anything security-sensitive. The default simply throws.
     */
    public val onError: suspend context(ServerRuntime) (OauthCode) -> HttpResponse = {
        throw Exception("Got Oauth error from ${oauthProviderInfo.niceName}: $it")
    },
    public val onAccess: suspend context(ServerRuntime) (OauthResponse, STATE) -> HttpResponse,
) : ServerBuilder() {

    /**
     * Transient record for a single in-progress OAuth flow, keyed in the cache by the opaque `state`
     * nonce that the provider echoes back. Storing both values together means one cache lookup on the
     * callback validates the CSRF `state` and retrieves the PKCE verifier and caller state.
     *
     * `internal` (not `public`) so in-module tests can inspect stored flows; not part of the public API.
     */
    @Serializable
    internal data class FlowRecord(
        /** The caller's `STATE` value, serialized with [stateSerializer], preserved across the round-trip. */
        val state: String,
        /** The PKCE code verifier for this flow, or null when the provider does not support PKCE. */
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
