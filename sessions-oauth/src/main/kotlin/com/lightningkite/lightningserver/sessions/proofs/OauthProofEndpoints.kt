package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.sessions.proofs.oauth.OauthProviderCredentials
import com.lightningkite.lightningserver.sessions.proofs.oauth.OauthProviderInfo
import com.lightningkite.lightningserver.typed.*
import kotlinx.datetime.*
import java.util.*

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
    proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proof"),
    private val provider: OauthProviderInfo,
    credentials: () -> OauthProviderCredentials,
    private val continueUiAuthUrl: ()->String
) : ServerBuilder(), ExternalProofMethod {

    init {
        proofMethods.register(this)
    }

    override val info: ProofMethodInfo = ProofMethodInfo(
        via = provider.identifierName,
        property = "email",
        strength = 10
    )

    val callback = path.path("callback").oauthCallback<UUID>(
        oauthProviderInfo = provider,
        credentials = credentials
    ) { response, _ ->
        val profile = provider.getProfile(response)
        val email = profile.email ?: throw BadRequestException("No email was found for this profile.")
        HttpResponse.redirectToGet(continueUiAuthUrl() + "?proof=${Serialization.json.encodeToString(Proof.serializer(), proofHasher().makeProof(
            info = info,
            property = "email",
            value = email,
            at = now()
        )).encodeURLQueryComponent()}&backend=${generalSettings().publicUrl.encodeURLQueryComponent()}")
    }

    override val indirectLink: ServerPath = path("open").get.handler {
        HttpResponse.redirectToGet(callback.loginUrl(UUID.random()))
    }.path

    val loginApi = path("login").get.api(
        summary = "Log In via ${provider.niceName}",
        authOptions = noAuth,
        description = "Returns a URL which, when opened in a browser, will allow you to log into the system with ${provider.niceName}.",
        errorCases = listOf(),
        examples = listOf(
            ApiExample(
                Unit,
                "${provider.loginUrl}?someparams=x"
            )
        ),
        implementation = { _: Unit ->
            callback.loginUrl(UUID.random())
        }
    )
    override val start = path("start").get.api(
        summary = "Log In via ${provider.niceName}",
        authOptions = noAuth,
        description = "Returns a URL which, when opened in a browser, will allow you to log into the system with ${provider.niceName}.",
        errorCases = listOf(),
        examples = listOf(
            ApiExample(
                "joseph@lightningkite.com",
                "${provider.loginUrl}?someparams=x"
            )
        ),
        implementation = { ensureEmail: String ->
            callback.loginUrl(UUID.random(), loginHint = ensureEmail)
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