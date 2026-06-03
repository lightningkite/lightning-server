package com.lightningkite.lightningserver.sessions.openid

import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.fastHash
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.database.*
import dev.whyoleg.cryptography.random.CryptographyRandom
import java.util.Base64

/**
 * Admin endpoints for registering and maintaining OAuth/OpenID Connect client applications.
 *
 * Registration is intentionally restricted to maintainers (super users by default): creating a
 * client establishes who is allowed to obtain tokens from this provider, so it is a privileged
 * operation. Relying parties do not read these records through this API — they only need the
 * `client_id` and a secret handed to them out of band.
 *
 * Provides standard REST CRUD over [OauthClient] plus a [createSecret] action that mints a new
 * client secret (returned in plaintext exactly once) supporting zero-downtime rotation.
 *
 * @param database Database runtime where clients are stored
 * @param maintainPermissions Who may manage clients (defaults to super users)
 */
public class OauthClientEndpoints(
    database: Runtime<Database>,
    private val maintainPermissions: AuthRequirement<*> = AuthRequirement.IsSuperUser,
) : ServerBuilder() {

    public val modelInfo: ModelInfo<HasId<*>?, OauthClient, String> = database.modelInfo(
        auth = maintainPermissions,
        permissions = {
            ModelPermissions(
                create = Condition.Always,
                read = Condition.Always,
                update = Condition.Always,
                delete = Condition.Always,
            )
        },
    )

    public val rest: ModelRestEndpoints<HasId<*>?, OauthClient, String> = ModelRestEndpoints(modelInfo)

    /**
     * POST /{_id}/create-secret
     *
     * Generates a new client secret for the given client and returns it in plaintext. This is the
     * only time the plaintext is available; it is stored only as a hash. Multiple secrets may be
     * active at once to allow rotation without downtime.
     */
    public val createSecret: ApiHttpHandler<PathSpec1<String>, HasId<*>?, Unit, String> =
        path.arg<String>("_id").path("create-secret").post bind ApiHttpHandler(
            auth = maintainPermissions,
            summary = "Create Client Secret",
            description = "Generates a new client secret (returned in plaintext exactly once) for the given OAuth client.",
            errorCases = listOf(
                LSError(http = 404, detail = "not-found", message = "OAuth client not found")
            ),
            successCode = HttpStatus.OK,
            implementation = { _: Unit ->
                // CryptographyRandom (CSPRNG): a client secret is a confidential-client credential, so it
                // must be unpredictable. 24 bytes = 192 bits of entropy.
                val newSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(CryptographyRandom.nextBytes(24))
                modelInfo.table().updateOneById(route.arg1, modification {
                    it.secrets += OauthClientSecret(
                        // Last-4 recognition hint so maintainers can identify which secret to rotate/disable.
                        // ~24 bits of a 192-bit secret — useless for reconstruction, useful for matching.
                        masked = "…" + newSecret.takeLast(4),
                        // fastHash (salted SHA-256), matching SessionManager's handling of session secrets:
                        // the secret is already high-entropy, so PBKDF2's slow iterations add no security but
                        // would let an attacker exhaust CPU by hammering the token endpoint. See SecureHash.fastHash.
                        secretHash = newSecret.fastHash(),
                        createdAt = now(),
                    )
                }).new ?: throw NotFoundException()
                newSecret
            }
        )
}
