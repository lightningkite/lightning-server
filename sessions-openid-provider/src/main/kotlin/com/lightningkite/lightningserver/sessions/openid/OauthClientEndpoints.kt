package com.lightningkite.lightningserver.sessions.openid

import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.secureHash
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.database.*
import java.util.*
import kotlin.random.Random

/**
 * OAuth Client Management Endpoints
 *
 * Provides CRUD operations for managing OAuth 2.0 / OpenID Connect client applications
 * that can authenticate users via this server.
 *
 * **What are OAuth Clients?**
 * When your server acts as an OAuth/OpenID Provider, external applications (clients)
 * need to register with you to obtain client credentials (client_id and client_secret).
 * This endpoint set allows managing those client registrations.
 *
 * **Features:**
 * - CRUD operations for OAuth clients
 * - Client secret generation and rotation
 * - Scope management per client
 * - Redirect URI validation
 * - Secret masking for security
 *
 * **Example Usage:**
 * ```kotlin
 * val oauthClients = OauthClientEndpoints(
 *     database = database,
 *     maintainPermissions = AuthRequirement.IsSuperUser
 * )
 *
 * // Include in your server
 * path.path("admin").path("oauth-clients") include oauthClients.rest
 * ```
 *
 * @param database Database runtime for storing client configurations
 * @param maintainPermissions Auth requirement for managing clients (default: super user only)
 */
public class OauthClientEndpoints(
    database: Runtime<Database>,
    private val maintainPermissions: AuthRequirement<*> = AuthRequirement.IsSuperUser
): ServerBuilder() {

    /**
     * Model info with permissions for OAuth client management
     *
     * Read access: Available to all (but secrets are masked for non-admins)
     * Manage access: Only available to users meeting maintainPermissions
     */
    public val modelInfo: ModelInfo<HasId<*>?, OauthClient, String> = database.modelInfo(
        auth = maintainPermissions or noAuth,
        permissions = {
            val isRoot = maintainPermissions.accepts(authOrNull)
            ModelPermissions(
                read = condition(true),
                readMask = mask {
                    it.secrets.maskedTo(setOf()).unless(condition(isRoot))
                },
                manage = condition(isRoot)
            )
        },
    )

    /**
     * REST endpoints for OAuth client CRUD operations
     *
     * Provides standard create, read, update, delete operations for OAuth clients.
     * Secrets are automatically masked for non-admin users.
     */
    public val rest: ModelRestEndpoints<HasId<*>?, OauthClient, String> = ModelRestEndpoints(modelInfo)

    /**
     * Create a new client secret
     *
     * **Endpoint:** POST /{_id}/create-secret
     *
     * Generates a new random client secret for the specified OAuth client.
     * Returns the plain-text secret (only time it's visible). The secret is hashed
     * before storage and partially masked for display.
     *
     * Supports secret rotation: Multiple secrets can be active simultaneously,
     * allowing clients to update their credentials without downtime.
     *
     * @return The newly generated client secret (plain text)
     */
    public val createSecret: ApiHttpHandler<PathSpec1<String>, HasId<*>?, Unit, String?> =
        path.arg<String>("_id").path("create-secret").post bind ApiHttpHandler(
        auth = maintainPermissions,
        summary = "Create Secret",
        description = """
            Generates a new client secret for the specified OAuth client.

            The secret is returned in plain text (this is the ONLY time it will be visible).
            Store it securely as it cannot be retrieved later.

            The secret is hashed using SHA-256 before storage. A masked version is stored
            for display purposes (e.g., "abc***xyz").

            Multiple secrets can exist per client to enable zero-downtime rotation.
        """.trimIndent(),
        errorCases = listOf(
            LSError(http = 404, detail = "not-found", message = "OAuth client not found")
        ),
        successCode = com.lightningkite.lightningserver.http.HttpStatus.OK,
        implementation = { _: Unit ->
            val newSecret = Base64.getEncoder().encodeToString(Random.nextBytes(24))
            modelInfo.table().updateOneById(request.arg1, modification {
                it.secrets += OauthClientSecret(
                    // Use fixed-length masking to prevent information leakage
                    masked = "[REDACTED]",
                    secretHash = newSecret.secureHash(),
                    createdAt = now()
                )
            }).new ?: throw NotFoundException()
            newSecret
        }
    )
}
