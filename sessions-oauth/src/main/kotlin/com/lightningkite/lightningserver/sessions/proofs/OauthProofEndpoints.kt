package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.redirectToGet
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.serialization.serializerOrContextual
import com.lightningkite.lightningserver.sessions.proofs.extensions.makeProof
import com.lightningkite.lightningserver.sessions.proofs.oauth.*
import com.lightningkite.lightningserver.sessions.proofs.oauth.OauthCallbackEndpoint
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.sdk.SdkModule
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.lightningserver.typed.sdk.sdkSettings
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.HasId
import io.ktor.http.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * Provides OAuth-based authentication proof endpoints for external identity providers.
 *
 * This class creates endpoints that enable users to authenticate via OAuth providers like
 * Google, Apple, Microsoft, and GitHub. OAuth authentication provides the highest proof
 * strength (10) because the identity is verified by a trusted third party.
 *
 * **Authentication Flow:**
 * 1. Client calls `start` or `loginApi` to get an OAuth authorization URL
 * 2. User is redirected to the provider (Google, Apple, etc.)
 * 3. Provider authenticates the user and redirects back to the callback endpoint
 * 4. Callback exchanges the authorization code for an access token
 * 5. User profile is retrieved from the provider
 * 6. Email is extracted and wrapped in a cryptographically signed proof
 * 7. User is redirected back to the UI with the proof as a query parameter
 *
 * **Proof Strength:** 10 (highest) - OAuth verification by trusted identity provider
 *
 * **Example usage:**
 * ```kotlin
 * val googleAuth = OauthProofEndpoints(
 *     provider = OauthProviderInfo.google,
 *     credentials = { googleOAuthCredentials() },
 *     continueUiAuthUrl = { "https://myapp.com/auth/continue" }
 * )
 * ```
 *
 * **Security:** The flow is protected against CSRF via a single-use `state` nonce and against
 * authorization-code interception via PKCE (RFC 7636). Both are held in [cache] for the duration of
 * the redirect round-trip and validated/consumed on the callback.
 *
 * @param proofSigner The signer used to create cryptographic proofs (defaults to derived from secretBasis)
 * @param provider The OAuth provider configuration (Google, Apple, Microsoft, GitHub, or custom)
 * @param cache Cache holding the transient CSRF `state` and PKCE verifier between redirect and callback
 * @param credentials Function that returns the OAuth client credentials (ID and secret)
 * @param continueUiAuthUrl Function that returns the UI URL to redirect to after successful authentication
 *
 * @see OauthProviderInfo for built-in providers (Google, Apple, Microsoft, GitHub)
 * @see ExternalProofMethod
 */
public class OauthProofEndpoints(
    private val provider: OauthProviderInfo,
    private val cache: Runtime<Cache>,
    override val proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proof"),
    override val proofExpiration: Duration = 1.hours,
    private val credentials: Runtime<OauthProviderCredentials>,
    private val makeProof: suspend context(ServerRuntime, ProofMethod) (ExternalProfile) -> Proof =
        { profile ->
            val email = profile.email ?: throw BadRequestException("No email was found for this profile.")
            proofSigner.await().makeProof(property = "email", value = email)
        },
    private val continueUiAuthUrl: context(ServerRuntime) (Proof) -> String,
) : ServerBuilder(), ExternalProofMethod {

    init {
        proofMethodsRegistry.register(this)
        sdkSettings.defaultInfo = SdkModule.Info(
            interfaceName = "${provider.niceName}OAuth",
            valueName = provider.identifierName
        )
        // TODO: Add OAuth interface to ProofClientEndpoints in sessions-oauth-shared module
        // sdkSettings.clientInterface = ProofClientEndpoints.OAuth::class.info()
    }

    override val info: ProofMethodInfo = ProofMethodInfo(
        via = provider.identifierName,
        property = "email",
        strength = 10
    )

    public val callback: OauthCallbackEndpoint<Uuid> = path.path("callback") include OauthCallbackEndpoint(
        path = path,
        stateSerializer = serializerOrContextual<Uuid>(),
        oauthProviderInfo = provider,
        credentials = credentials,
        cache = cache,
    ) { response: OauthResponse, _: Uuid ->
        val profile = provider.getProfile(response, credentials())
        // Open-redirect note: the final destination is produced entirely by the app-supplied
        // `continueUiAuthUrl` from a server-generated, signed Proof. No user- or attacker-controllable
        // value (query param or `state`) feeds into it, so the redirect target is app-controlled and
        // does not require redirect-URI whitelisting here.
        HttpResponse.redirectToGet(continueUiAuthUrl(makeProof(profile)))
    }

    public val openEndpoint: HttpHandler<*> = path.path("open").get bind HttpHandler {
        HttpResponse.redirectToGet(callback.loginUrl(Uuid.random()))
    }

    context(_: ServerRuntime)
    public val loginApi: ApiHttpHandler<*, *, Unit, String>
        get() = path.path("login").get bind ApiHttpHandler(
            auth = noAuth,
            summary = "Log In via ${provider.niceName}",
            description = "Returns a URL which, when opened in a browser, will allow you to log into the system with ${provider.niceName}.",
            errorCases = listOf(),
            successCode = HttpStatus.OK,
            examples = listOf(
                ApiHttpHandler.Example(
                    input = Unit,
                    output = "${provider.loginUrl}?someparams=x"
                )
            ),
            implementation = { _: Unit ->
                callback.loginUrl(Uuid.random())
            }
        )

    override val start: ApiHttpHandler<PathSpec0, HasId<*>?, String, String> =
        path.path("start").get bind ApiHttpHandler(
            auth = noAuth,
            summary = "Log In via ${provider.niceName}",
            description = "Returns a URL which, when opened in a browser, will allow you to log into the system with ${provider.niceName}.",
            errorCases = listOf(),
            successCode = HttpStatus.OK,
            examples = listOf(
                ApiHttpHandler.Example(
                    input = "joseph@lightningkite.com",
                    output = "${provider.loginUrl}?someparams=x"
                )
            ),
            implementation = { ensureEmail: String ->
                callback.loginUrl(Uuid.random(), loginHint = ensureEmail)
            }
        )
}

/*
 * TODO: API Recommendations
 *
 * 1. The callback endpoint constructs a redirect URL with manually encoded query parameters.
 *    Consider using a URL builder utility for safety and readability.
 *
 * 2. The continueUiAuthUrl function returns a String, but it's concatenated with query params.
 *    Consider documenting that it should NOT include a trailing '?' or existing query params,
 *    or make it more robust by handling both cases.
 *
 * 3. Consider adding error handling for when profile.email is null with more specific error messages
 *    indicating which OAuth provider failed to provide an email.
 *
 * 4. The 'backend' query parameter is added to the redirect but never used in the documented flow.
 *    Consider documenting its purpose or removing it if unused.
 *
 * 5. Consider adding telemetry/metrics for OAuth login attempts, successes, and failures
 *    to help diagnose provider-specific issues.
 */