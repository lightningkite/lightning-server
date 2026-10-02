package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.sessions.proofs.extensions.constrainAttemptRate
import com.lightningkite.lightningserver.sessions.proofs.extensions.makeProof
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.IndexSet
import com.lightningkite.services.database.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * One unused backup code belonging to a subject. Each row is deleted as the code is used.
 *
 * @property code The Base64 SHA-256 hash of the normalized code (letters only, lowercase). Rows written before
 * hashing was introduced hold the normalized code in plain text instead; [BackupCodeEndpoints.prove] accepts both.
 * @property subjectId The owning subject's id, as produced by [PrincipalType.idString].
 * @property subjectType The owning subject's [PrincipalType.name].
 */
@Serializable
@GenerateDataClassPaths
@IndexSet(["subjectId", "subjectType"])
public data class BackupCodeSecret(
    override val _id: Uuid = Uuid.random(),
    val code: String,
    val subjectId: String,
    val subjectType: String,
) : HasId<Uuid>

/**
 * Provides single-use backup codes as a [ProofMethod], for a subject who has lost access to another factor.
 *
 * ## Setup flow
 *
 * 1. An authenticated subject calls [resetCodes] and receives [generateCount] new codes
 * 2. The client shows them once; the server keeps only their hashes, so they can never be shown again
 * 3. Calling [resetCodes] again replaces every existing code, and [clearCodes] removes them all
 *
 * All management endpoints require [proofMethodAuth], whose `maxAge` is 10 minutes: codes can only be
 * viewed or replaced by someone who authenticated *recently*, not merely by someone holding a long-lived session.
 *
 * ## Authentication flow
 *
 * 1. Client calls [prove] with an identifier for the subject and one of their codes
 * 2. The code is consumed and the client receives a signed [Proof]
 * 3. Client submits that proof to `AuthEndpoints.login`, usually alongside a proof from another method
 *
 * ## Strength
 *
 * [info] reports a strength of 0, but the proofs [prove] issues carry a strength of 10. Reporting 0 means that
 * having backup codes never raises the strength a subject must reach to log in, while each backup code proof
 * still counts 10 at login, the same as a password or TOTP proof. A backup code can therefore stand in for any
 * one factor of strength 10. Several backup code proofs do not stack, since proofs from one method count only once.
 *
 * Nothing prevents a backup code from being the only proof at login. If `requiredProofStrengthFor` returns 10
 * or less for a subject, a backup code alone is enough to log in.
 *
 * ## Code format
 *
 * Codes are [codeLength] letters drawn with [SecureRandom] from A–Z without I and O, rerolled if they match
 * [BadWordList]. The default of 20 letters carries about 91 bits of entropy. They are returned in groups of five
 * separated by dashes, and [prove] ignores dashes, case, and any other non-letter characters.
 *
 * @param database The database that stores [BackupCodeSecret] rows.
 * @param cache The cache that backs the rate limit on [prove].
 * @param proofSigner Signs the proofs [prove] issues.
 * @param proofExpiration How long an issued proof stays valid.
 * @param codeLength The number of letters in each code. Codes are stored as unsalted hashes, so a much shorter
 * code would make a leaked table cheap to crack.
 * @param generateCount The number of codes [resetCodes] generates.
 */
public class BackupCodeEndpoints(
    database: Runtime<Database>,
    private val cache: Runtime<Cache>,
    override val proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proof"),
    override val proofExpiration: Duration = 1.hours,
    private val codeLength: Int = 20,
    private val generateCount: Int = 10, // The number of codes to generate
) : ServerBuilder(), DirectProofMethod {

    init {
        proofMethodsRegistry.register(this)

        sdkSettings.defaultInfo = SdkModule.Info("BackupCodeProof", "backupCode")
        sdkSettings.clientInterface = ProofClientEndpoints.BackupCode::class.info()
    }

    override val info: ProofMethodInfo = ProofMethodInfo(
        via = "backupcode",
        property = null,
        strength = 0
    )

    private val availableCharacters = ('A'..'Z').toList() - setOf('I', 'O')

    public val modelInfo: ModelInfo<HasId<*>?, BackupCodeSecret, Uuid> =
        database.modelInfo(
            auth = noAuth,
            tableName = "BackupCodeSecret",
            permissions = { ModelPermissions<BackupCodeSecret>(all = Condition.Never) }
        )

    // Unsalted is fine since codes must be deterministic to query by it.
    private fun codeHash(normalizedCode: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(normalizedCode.toByteArray()))

    /** Reduces a code as typed by a user to the canonical form that is hashed: letters only, lowercase. */
    private fun normalizeCode(rawCode: String): String = rawCode.filter { it.isLetter() }.lowercase()

    /**
     * POST /reset-codes
     *
     * Replaces the calling subject's backup codes with [generateCount] new ones.
     *
     * **Authentication:** [proofMethodAuth]. The 10-minute `maxAge` means the caller must have authenticated
     * recently, since a fresh set of codes is a way back into the account.
     *
     * **Request Body:** None.
     *
     * **Response:** The new codes in plain text, formatted as groups of five letters separated by dashes
     * (e.g. `ABCDE-FGHJK-LMNPQ-RSTUV`). This is the only time they are available; only their hashes are stored.
     *
     * Every existing code for the subject is deleted first, used or not.
     */
    public val resetCodes: ApiHttpHandler<PathSpec0, HasId<*>, Unit, List<String>> =
        path.path("reset-codes").post bind explicitApiHttpHandler(
            summary = "Reset Codes",
            inputType = Unit.serializer(),
            outputType = ListSerializer(String.serializer()),
            description = "Reset your existing backup codes with new ones",
            auth = proofMethodAuth,
            errorCases = listOf(),
            examples = listOf(),
            implementation = { _: Unit ->
                modelInfo.table().deleteManyIgnoringOld(
                    condition { it.subjectId.eq(auth.rawId) and it.subjectType.eq(auth.principalName) }
                )

                val r = SecureRandom()

                val newCodes = (0..<generateCount).map {
                    var code: String
                    do {
                        code =
                            String(CharArray(codeLength) { availableCharacters[r.nextInt(availableCharacters.size)] })
                    } while (BadWordList.detectParanoid(code))
                    code
                }

                modelInfo.table().insert(newCodes.map {
                    BackupCodeSecret(
                        code = codeHash(normalizeCode(it)),
                        subjectId = auth.rawId,
                        subjectType = auth.principalName,
                    )
                })

                newCodes.map { code -> code.chunked(5).joinToString("-") }
            }
        )

    /**
     * POST /clear-codes
     *
     * Deletes all of the calling subject's backup codes, after which [established] reports `false`.
     *
     * **Authentication:** [proofMethodAuth].
     *
     * **Request Body:** None.
     *
     * **Response:** None.
     */
    public val clearCodes: ApiHttpHandler<PathSpec0, HasId<*>, Unit, Unit> =
        path.path("clear-codes").post bind explicitApiHttpHandler(
            summary = "Clear Codes",
            inputType = Unit.serializer(),
            outputType = Unit.serializer(),
            description = "Removes all backup codes for the user",
            auth = proofMethodAuth,
            errorCases = listOf(),
            examples = listOf(),
            implementation = { _: Unit ->
                modelInfo.table().deleteManyIgnoringOld(
                    condition { it.subjectId.eq(auth.rawId) and it.subjectType.eq(auth.principalName) }
                )
            }
        )

    /**
     * GET /established
     *
     * Reports whether the calling subject has at least one unused backup code.
     *
     * **Authentication:** [proofMethodAuth].
     *
     * **Request Body:** None.
     *
     * **Response:** `true` if any code remains, `false` if none were generated or all have been used or cleared.
     */
    public val established: ApiHttpHandler<PathSpec0, HasId<*>, Unit, Boolean> =
        path.path("established").get bind explicitApiHttpHandler(
            summary = "Established",
            inputType = Unit.serializer(),
            outputType = Boolean.serializer(),
            description = "Returns whether a user has valid backup codes established",
            auth = proofMethodAuth,
            errorCases = listOf(),
            examples = listOf(),
            implementation = { _: Unit ->
                modelInfo.table().findOne(
                    condition { it.subjectId.eq(auth.rawId) and it.subjectType.eq(auth.principalName) }
                ) != null
            }
        )

    /**
     * POST /prove
     *
     * Consumes one of a subject's backup codes and issues a [Proof] for them.
     *
     * **Authentication:** None, since this is how a subject logs in.
     *
     * **Request Body:** [IdentificationAndPassword]
     * - `type`: The [PrincipalType.name] of the subject, e.g. `User`
     * - `property`: The property identifying the subject, e.g. `email`
     * - `value`: That property's value; it is normalized with [PrincipalType.normalizePropertyValue]
     * - `password`: The backup code; dashes, case, and other non-letter characters are ignored
     *
     * **Response:** A [Proof] for `property` and the normalized `value`, with strength 10, valid for [proofExpiration].
     *
     * **Errors:** An unknown subject and a wrong code both fail with the same "Invalid Backup Code" error, so the
     * response does not reveal whether an account exists.
     *
     * **Rate limiting:** Attempts are limited per property and normalized value, with exponential backoff (see
     * [constrainAttemptRate]). Each failed attempt also takes a minimum amount of time, so response timing does
     * not reveal whether an account exists either.
     *
     * The code is claimed by deleting its row, so it cannot be used twice. This relies on the database backend's
     * `deleteOne` being atomic.
     */
    public override val prove: ApiHttpHandler<PathSpec0, HasId<*>?, IdentificationAndPassword, Proof> =
        path.path("prove").post bind ApiHttpHandler(
            auth = noAuth,
            summary = "Prove With Backup Code",
            description = "Use an established backup code as an authentication method.",
            errorCases = listOf(),
            examples = listOf(
                ApiHttpHandler.Example(
                    input = IdentificationAndPassword(
                        "User",
                        "email",
                        "test@test.com",
                        "akduvuiwkd-adffddfafd"
                    ),
                    output = Proof(
                        via = info.via,
                        property = "email",
                        strength = info.strength,
                        value = "test@test.com",
                        at = Clock.System.now(),
                        expiresAt = Clock.System.now() + proofExpiration,
                        signature = "opaquesignaturevalue"
                    )
                )
            ),
            successCode = HttpStatus.OK,
            implementation = { input: IdentificationAndPassword ->

                val subject = input.type

                val handler = serverRuntime.server.principalTypes.values.find { it.name == subject }
                    ?: throw BadRequestException("No subject $subject recognized")

                // Normalize BEFORE building the rate-limit key: the key must be derived from the canonical
                // identifier so that case/whitespace variants of the same account share one bucket. Keying on
                // the raw value would let an attacker dodge the limiter (and its exponential backoff) simply
                // by varying case or whitespace.
                val normalizedValue = handler.normalizePropertyValue(input.property, input.value)
                cache().constrainAttemptRate(
                    cacheKey = "backup-code-count-${input.property}-${normalizedValue}"
                ) {
                    val subjectId = handler.fetchUserIdString(input.property, normalizedValue)
                        ?: throw BadRequestException("Invalid Backup Code")

                    val normalizedCode = normalizeCode(input.password)

                    // Codes stored before hashing was introduced are plain text.
                    modelInfo.table().deleteOne(condition {
                        it.subjectId.eq(subjectId) and
                                it.subjectType.eq(subject) and
                                it.code.inside(listOf(codeHash(normalizedCode), normalizedCode))
                    }) ?: throw BadRequestException("Invalid Backup Code")

                    proofSigner.await().makeProof(
                        info = info.copy(strength = 10),
                        property = input.property,
                        value = normalizedValue,
                    )
                }
            }
        )

    /** Whether [subject] has at least one unused backup code, which makes this method available to them at login. */
    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> established(
        principal: PrincipalType<SUBJECT, ID>,
        subject: SUBJECT,
    ): Boolean =
        modelInfo.table().findOne(
            condition {
                it.subjectId.eq(principal.idString(subject._id)) and
                        it.subjectType.eq(principal.name)
            }
        ) != null
}