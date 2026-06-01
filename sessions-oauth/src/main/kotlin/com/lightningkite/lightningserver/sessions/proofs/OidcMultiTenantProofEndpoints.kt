package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.cipherBlocking
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.pathing.fullUrl
import com.lightningkite.lightningserver.redirectToGet
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.serialization.queryParameters
import com.lightningkite.lightningserver.sessions.proofs.extensions.makeProof
import com.lightningkite.lightningserver.sessions.proofs.oauth.*
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.lightningserver.typed.explicitApiHttpHandler
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.lightningserver.typed.route
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.get
import com.lightningkite.services.cache.set
import com.lightningkite.services.database.*
import dev.whyoleg.cryptography.operations.Cipher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Multi-tenant OpenID Connect proof endpoints.
 *
 * Each customer's IdP is stored as an [OidcTenantConfig] row keyed by a URL-safe slug.
 * Login and callback URLs include the slug, so different customers route to different IdPs
 * without any code changes:
 *
 * ```
 * GET  /{mount}/{tenant}/login    → typed; returns IdP authorization URL
 * GET  /{mount}/{tenant}/callback → IdP redirect target; produces signed Proof
 * ```
 *
 * **Admin REST.** The tenant table is exposed at `/{mount}/tenants` via [ModelRestEndpoints],
 * gated behind [AuthRequirement.IsAdmin]. The client secret is encrypted on insert, masked on
 * read, and forbidden in PATCH; the [rotateSecret] endpoint at
 * `/{mount}/tenants/{_id}/rotate-secret` accepts a new plaintext value and writes the
 * re-encrypted ciphertext.
 *
 * **Security defaults.**
 * - Client secrets are stored encrypted with the server's `secretBasis` AES-GCM cipher.
 * - PKCE (S256) is enabled unconditionally. The verifier is held in [cache] and never
 *   leaves the server.
 * - The `nonce` claim is verified against the value we sent in the auth request.
 * - `email_verified` is required by default; tenants whose IdPs do not assert this cannot
 *   issue trusted proofs unless their config explicitly sets `requireEmailVerified=false`.
 *
 * Issued proofs carry `via="oidc"`, `property="email"`, and the tenant's configured
 * [OidcTenantConfig.strength]. The default tenant strength of 10 matches the single-IdP
 * [OauthProofEndpoints] behavior.
 */
public class OidcMultiTenantProofEndpoints(
    private val database: Runtime<Database>,
    private val cache: Runtime<Cache>,
    public val secretCipher: Runtime<Cipher> = secretBasis.cipherBlocking("oidc-tenant"),
    override val proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proof"),
    override val proofExpiration: Duration = 1.hours,
    private val continueUiAuthUrl: context(ServerRuntime) (Proof) -> String,
) : ServerBuilder(), ProofMethod {

    init {
        proofMethodsRegistry.register(this)
    }

    override val info: ProofMethodInfo = ProofMethodInfo(
        via = "oidc",
        property = "email",
        strength = 10,
    )

    /**
     * [ModelInfo] for the tenant config table. Internal callsites use `tenants.table()`
     * (system access) to bypass the read mask and the cannot-be-modified restriction on the
     * client secret. The REST surface uses `tenants.table(auth)` which applies both.
     */
    public val tenants: ModelInfo<HasId<*>, OidcTenantConfig, String> = database.modelInfo(
        auth = AuthRequirement.IsAdmin,
        signals = { col ->
            val cipher = secretCipher()
            col.interceptCreate { tenant ->
                tenant.copy(clientSecret = encryptSecretBytes(cipher, tenant.clientSecret))
            }
        },
        permissions = {
            ModelPermissions(
                create = Condition.Always,
                read = Condition.Always,
                readMask = mask {
                    it.clientSecret.mask(MASKED_SECRET)
                },
                update = Condition.Always,
                updateRestrictions = updateRestrictions {
                    it._id.cannotBeModified()
                    it.clientSecret.cannotBeModified()
                    it.createdAt.cannotBeModified()
                },
                delete = Condition.Always,
            )
        }
    )

    /**
     * `* /tenants` — full REST surface for admin-managed tenant configuration.
     *
     * Auth is asserted by the underlying [tenants] ModelInfo (admin-only). Use the admin
     * UI to add a tenant; supply the plaintext `clientSecret` in the create body and it
     * will be encrypted before insert.
     */
    public val rest: ModelRestEndpoints<HasId<*>, OidcTenantConfig, String> =
        path.path("tenants") include ModelRestEndpoints(tenants)

    /**
     * `POST /tenants/{_id}/rotate-secret` — replaces the stored client secret with a new
     * plaintext value submitted by the admin. The value is encrypted before write.
     */
    public val rotateSecret: ApiHttpHandler<PathSpec1<String>, HasId<*>, String, Unit> =
        path.path("tenants").arg<String>("_id").path("rotate-secret").post bind explicitApiHttpHandler(
            auth = AuthRequirement.IsAdmin,
            inputType = String.serializer(),
            outputType = Unit.serializer(),
            summary = "Rotate OIDC tenant client secret",
            description = "Submit the new plaintext client secret. It will be encrypted and stored. " +
                    "Existing PKCE/auth flows in flight are unaffected.",
            errorCases = listOf(),
            implementation = { plaintextSecret: String ->
                val tenantId = route.arg1
                val cipher = secretCipher()
                val encrypted = encryptSecretBytes(cipher, plaintextSecret)
                tenants.table().updateOneById(
                    tenantId,
                    modification<OidcTenantConfig> { it.clientSecret assign encrypted }
                ).new ?: throw NotFoundException("oidc-tenant-not-found", "Unknown OIDC tenant '$tenantId'")
                Unit
            }
        )

    private val discoveryMutex = Mutex()
    private val discoveryByTenant = HashMap<String, Pair<String, OidcDiscovery>>()

    private suspend fun discoveryFor(tenant: OidcTenantConfig): OidcDiscovery = discoveryMutex.withLock {
        val current = discoveryByTenant[tenant._id]
        if (current != null && current.first == tenant.discoveryUrl) current.second
        else OidcDiscovery(tenant.discoveryUrl).also {
            discoveryByTenant[tenant._id] = tenant.discoveryUrl to it
        }
    }

    context(_: ServerRuntime)
    private suspend fun loadTenant(tenantId: String): OidcTenantConfig {
        val tenant = tenants.table().get(tenantId)
            ?: throw NotFoundException("oidc-tenant-not-found", "Unknown OIDC tenant '$tenantId'")
        if (tenant.disabledAt != null) {
            throw BadRequestException("OIDC tenant '$tenantId' is disabled")
        }
        return tenant
    }

    @OptIn(ExperimentalEncodingApi::class)
    context(_: ServerRuntime)
    private suspend fun decryptSecret(encryptedBase64: String): String =
        secretCipher().decrypt(Base64.decode(encryptedBase64)).decodeToString()

    private fun providerFor(
        tenant: OidcTenantConfig,
        discovery: OidcDiscovery,
    ): OauthProviderInfo = OauthProviderInfo(
        niceName = tenant.niceName,
        loginUrl = "",
        tokenUrl = "",
        mode = OauthResponseMode.query,
        scopeForProfile = (setOf("openid", "email", "profile") + tenant.extraScopes).joinToString(" "),
        resolveEndpoints = {
            val doc = discovery.document()
            OauthProviderInfo.Endpoints(doc.authorization_endpoint, doc.token_endpoint)
        },
        getProfile = { _, _ -> throw UnsupportedOperationException("getProfile is unused in OIDC multi-tenant flow") }
    )

    private fun pkceCacheKey(tenantId: String, encodedState: String): String =
        "oidc:pkce:$tenantId:$encodedState"

    /**
     * `GET /{tenant}/login` — returns a URL the client should redirect the user's browser to.
     */
    public val login: ApiHttpHandler<PathSpec1<String>, HasId<*>?, Unit, String> =
        path.arg<String>("tenant").path("login").get bind ApiHttpHandler(
            auth = noAuth,
            summary = "Begin OIDC login",
            description = "Returns an authorization URL the browser should be sent to. " +
                    "The IdP redirects back to this server's callback after authentication.",
            errorCases = listOf(),
            successCode = HttpStatus.OK,
            implementation = { _: Unit ->
                val tenantId = route.arg1
                val tenant = loadTenant(tenantId)
                val discovery = discoveryFor(tenant)
                val pair = Pkce.generate()
                val state = OidcCallbackState(tenantId = tenantId, nonce = Uuid.random().toString())
                val encodedState = serverRuntime.externalSerialization.json.encodeToString(
                    OidcCallbackState.serializer(), state
                )
                cache().set(
                    pkceCacheKey(tenantId, encodedState),
                    pair.verifier,
                    PKCE_TTL,
                )
                val credentials = tenantCredentials(tenant)
                providerFor(tenant, discovery).loginUrl(
                    credentials = credentials,
                    redirectUri = callback.location.path.resolved(tenantId).fullUrl(),
                    state = encodedState,
                    nonce = state.nonce,
                    codeChallenge = pair.challenge,
                )
            }
        )

    /**
     * `GET /{tenant}/callback?code=...&state=...` — the IdP redirect target. Completes the OIDC
     * authorization-code exchange, verifies the resulting ID token (signature, issuer, audience,
     * nonce, email_verified), and redirects the browser to [continueUiAuthUrl] with a signed Proof.
     */
    public val callback: HttpHandler<PathSpec1<String>> =
        path.arg<String>("tenant").path("callback").get bind HttpHandler { req ->
            val tenantId = req.path.arg1
            val oauthCode = req.queryParameters(OauthCode.serializer())
            oauthCode.error?.let { err ->
                throw BadRequestException("oidc-error", "IdP returned error: $err")
            }
            val encodedState = oauthCode.state
                ?: throw BadRequestException("oidc-missing-state", "Missing state in callback")
            val callbackState = serverRuntime.externalSerialization.json.decodeFromString(
                OidcCallbackState.serializer(), encodedState
            )
            if (callbackState.tenantId != tenantId) {
                throw BadRequestException("oidc-state-tenant-mismatch", "State tenant does not match URL tenant")
            }

            val tenant = loadTenant(tenantId)
            val discovery = discoveryFor(tenant)
            val verifier = cache().get<String>(pkceCacheKey(tenantId, encodedState))
                ?: throw BadRequestException("oidc-pkce-missing", "PKCE verifier missing or expired")
            cache().remove(pkceCacheKey(tenantId, encodedState))

            val credentials = tenantCredentials(tenant)
            val provider = providerFor(tenant, discovery)
            val tokenResponse = provider.accessToken(
                credentials = credentials,
                redirectUri = callback.location.path.resolved(tenantId).fullUrl(),
                oauth = oauthCode,
                codeVerifier = verifier,
            )

            val idToken = tokenResponse.id_token
                ?: throw BadRequestException("oidc-no-id-token", "IdP did not return an id_token; is 'openid' in scopes?")

            val doc = discovery.document()
            val claims = JwtVerifier(
                expectedIssuer = doc.issuer,
                expectedAudience = tenant.clientId,
                jwks = discovery.jwks(),
            ).verify(idToken, expectedNonce = callbackState.nonce)

            if (tenant.requireEmailVerified && claims.email_verified != true) {
                throw BadRequestException(
                    "oidc-email-unverified",
                    "Tenant '$tenantId' requires email_verified=true; IdP did not assert it"
                )
            }
            val email = when (tenant.emailClaim) {
                "email" -> claims.email
                "preferred_username" -> claims.preferred_username
                else -> throw BadRequestException(
                    "oidc-bad-email-claim",
                    "Unsupported emailClaim '${tenant.emailClaim}'"
                )
            } ?: throw BadRequestException(
                "oidc-no-email",
                "ID token from '$tenantId' did not include claim '${tenant.emailClaim}'"
            )

            val tenantInfo = info.copy(strength = tenant.strength)
            val proof = proofSigner.await().makeProof(
                info = tenantInfo,
                property = "email",
                value = email,
                at = now(),
                expireAfter = proofExpiration,
            )
            HttpResponse.redirectToGet(continueUiAuthUrl(proof))
        }

    context(_: ServerRuntime)
    private suspend fun tenantCredentials(tenant: OidcTenantConfig): Runtime<OauthProviderCredentials> {
        val secret = decryptSecret(tenant.clientSecret)
        val creds = OauthProviderCredentials(id = tenant.clientId, secret = secret)
        return Runtime { creds }
    }

    public companion object {
        public val PKCE_TTL: Duration = 10.minutes

        /**
         * Sentinel substituted for [OidcTenantConfig.clientSecret] when the row is read
         * through the admin REST surface. Never accepted as a write value — the
         * `cannotBeModified()` restriction blocks PATCHes of the field, so a round-trip
         * cannot accidentally re-encrypt the sentinel as the new secret.
         */
        public const val MASKED_SECRET: String = "********"

        @OptIn(ExperimentalEncodingApi::class)
        private suspend fun encryptSecretBytes(cipher: Cipher, plaintext: String): String =
            Base64.encode(cipher.encrypt(plaintext.encodeToByteArray()))
    }
}

/**
 * Round-trip state carried in the OIDC `state` parameter. The tenant id is included so the
 * callback can defensively confirm the IdP returned us to the same tenant it was started for,
 * and the nonce is bound to the id_token to defend against token replay.
 */
@Serializable
public data class OidcCallbackState(
    val tenantId: String,
    val nonce: String,
)
