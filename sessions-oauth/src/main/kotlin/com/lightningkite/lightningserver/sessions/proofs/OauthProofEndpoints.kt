package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.redirectToGet
import com.lightningkite.services.database.HasId
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.sessions.proofs.extensions.makeProof
import com.lightningkite.lightningserver.sessions.proofs.oauth.OauthCallbackEndpoint
import com.lightningkite.lightningserver.sessions.proofs.oauth.OauthProviderCredentials
import com.lightningkite.lightningserver.sessions.proofs.oauth.OauthProviderInfo
import com.lightningkite.lightningserver.sessions.proofs.oauth.oauthCallback
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.sdk.SdkModule
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.lightningserver.typed.sdk.clientInterface
import com.lightningkite.lightningserver.typed.sdk.info
import com.lightningkite.lightningserver.typed.sdk.sdkSettings
import io.ktor.http.*
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
 * @param proofSigner The signer used to create cryptographic proofs (defaults to derived from secretBasis)
 * @param provider The OAuth provider configuration (Google, Apple, Microsoft, GitHub, or custom)
 * @param credentials Function that returns the OAuth client credentials (ID and secret)
 * @param continueUiAuthUrl Function that returns the UI URL to redirect to after successful authentication
 *
 * @see OauthProviderInfo for built-in providers (Google, Apple, Microsoft, GitHub)
 * @see ExternalProofMethod
 */
public class OauthProofEndpoints(
    private val proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proof"),
    private val provider: OauthProviderInfo,
    private val credentials: () -> OauthProviderCredentials,
    private val continueUiAuthUrl: ()->String
) : ServerBuilder(), ExternalProofMethod {

    init {
        proofMethods.register(this)
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

    context(_: ServerRuntime)
    private suspend fun proofHasher(): Signer = proofSigner.await()

    public val callback: OauthCallbackEndpoint<Uuid> = path.path("callback").post.oauthCallback<Uuid>(
        oauthProviderInfo = provider,
        credentials = credentials
    ) { response, _ ->
        val profile = provider.getProfile(response, credentials())
        val email = profile.email ?: throw BadRequestException("No email was found for this profile.")
        HttpResponse.redirectToGet(continueUiAuthUrl() + "?proof=${
            serverRuntime.externalSerialization.json.encodeToString(Proof.serializer(), proofHasher().makeProof(
            info = info,
            property = "email",
            value = email,
            at = now()
        )).encodeURLQueryComponent()}&backend=${generalSettings().publicUrl.encodeURLQueryComponent()}")
    }

    public val openEndpoint: HttpHandler<*> = path.path("open").get bind HttpHandler {
        HttpResponse.redirectToGet(callback.loginUrl(Uuid.random()))
    }

    context(_: ServerRuntime)
    public val loginApi: ApiHttpHandler<*, *, Unit, String> get() = path.path("login").get bind ApiHttpHandler(
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

    override val start: ApiHttpHandler<PathSpec0, HasId<*>?, String, String> = path.path("start").get bind ApiHttpHandler(
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
 *
 * 6. The UUID state parameter in callback is generated but not validated. Consider using the
 *    state parameter for CSRF protection by storing and validating it.
 */