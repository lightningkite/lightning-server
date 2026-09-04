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
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.sessions.proofs.extensions.makeProof
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.IndexSet
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.security.SecureRandom
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

@Serializable
@GenerateDataClassPaths
@IndexSet(["subjectId", "subjectType"])
public data class BackupCodeSecret(
    override val _id: Uuid = Uuid.random(),
    val code: String,
    val subjectId: String,
    val subjectType: String,
    val createdAt: Instant,
    /**
     * When this code was redeemed, or null while it is still usable.
     *
     * Redeeming a code used to delete its row, which left no trace that the code had ever existed,
     * let alone been used — the one event on this table an auditor is certain to ask about. The row
     * is now retained and marked instead, and it is this field, not the row's absence, that makes a
     * code single-use.
     */
    val usedAt: Instant? = null,
) : HasId<Uuid>

@OptIn(InternalSerializationApi::class)
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

    public val resetCodes: ApiHttpHandler<PathSpec0, HasId<*>, Unit, List<String>> =
        path.path("reset-codes").post bind explicitApiHttpHandler(
            summary = "Reset Codes",
            inputType = Unit.serializer(),
            outputType = ListSerializer(String.serializer()),
            description = "Reset your existing backup codes with new ones. Input how many codes you wish to generate",
            auth = proofMethodAuth,
            errorCases = listOf(),
            examples = listOf(),
            implementation = { _: Unit ->
                // Revokes the codes that are still live. Redeemed rows stay: they are a record of an
                // authentication that happened, not a secret that needs withdrawing.
                modelInfo.table().deleteManyIgnoringOld(
                    condition {
                        it.subjectId.eq(auth.rawId) and it.subjectType.eq(auth.principalName) and
                                it.usedAt.eq(null)
                    }
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
                        code = it.lowercase(),
                        subjectId = auth.rawId,
                        subjectType = auth.principalName,
                        createdAt = now(),
                    )
                })

                newCodes.map { code -> code.chunked(5).joinToString("-") }
            }
        )

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

                // As above: clearing withdraws live codes, it does not erase redemption history.
                modelInfo.table().deleteManyIgnoringOld(
                    condition {
                        it.subjectId.eq(auth.rawId) and it.subjectType.eq(auth.principalName) and
                                it.usedAt.eq(null)
                    }
                )

                Unit
            }
        )

    public val established: ApiHttpHandler<PathSpec0, HasId<*>, Unit, Boolean> =
        path.path("established").get bind explicitApiHttpHandler(
            summary = "Established",
            inputType = Unit.serializer(),
            outputType = Boolean.serializer(),
            description = "Returns whether or a user has valid backup codes established",
            auth = proofMethodAuth,
            errorCases = listOf(),
            examples = listOf(),
            implementation = { _: Unit ->
                modelInfo.table().findOne(
                    condition {
                        it.subjectId.eq(auth.rawId) and it.subjectType.eq(auth.principalName) and
                                it.usedAt.eq(null)
                    }
                ) != null
            }
        )

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
                    ?: run {
                        reportProofRejected(info, ProofFailureReason.MalformedRequest, request = request)
                        throw IllegalArgumentException("No subject $subject recognized")
                    }

                // Normalize BEFORE building the rate-limit key: the key must be derived from the canonical
                // identifier so that case/whitespace variants of the same account share one bucket. Keying on
                // the raw value would let an attacker dodge the limiter (and its exponential backoff) simply
                // by varying case or whitespace.
                val normalizedValue = handler.normalizePropertyValue(input.property, input.value)
                cache().constrainProofAttemptRate(
                    cacheKey = "backup-code-count-${input.property}-${normalizedValue}",
                    method = info,
                    request = request,
                ) {
                    val subjectId = handler.fetchUserIdString(input.property, input.value)
                        ?: run {
                            // No account resolved, so no principal to name. Recorded anyway: a run of these
                            // against different values is what enumeration looks like.
                            reportProofRejected(info, ProofFailureReason.NoSuchSubject, request = request)
                            throw BadRequestException("Invalid Backup Code")
                        }

                    val secrets = modelInfo.table().find(condition {
                        it.subjectId.eq(subjectId) and
                                it.subjectType.eq(subject) and
                                it.usedAt.eq(null)
                    })
                        .toList()

                    val normalizedCode = input.password.filter { it.isLetter() }.lowercase()
                    val match = secrets.find { normalizedCode == it.code }
                        ?: run {
                            reportProofRejected(
                                info,
                                ProofFailureReason.SecretMismatch,
                                principal = subjectId,
                                request = request,
                            )
                            throw BadRequestException("Invalid Backup Code")
                        }

                    // Claim the code by marking it, conditional on it still being unused, so that two
                    // concurrent redemptions of the same code cannot both succeed. Losing this race is
                    // indistinguishable from presenting an already-spent code, and answers the same.
                    val claimed = modelInfo.table().updateOne(
                        condition { it._id.eq(match._id) and it.usedAt.eq(null) },
                        modification { it.usedAt assign now() },
                    )
                    if (claimed.old == null) {
                        reportProofRejected(
                            info,
                            ProofFailureReason.SecretAlreadyUsed,
                            principal = subjectId,
                            request = request,
                        )
                        throw BadRequestException("Invalid Backup Code")
                    }

                    reportProofAccepted(info, principal = subjectId, request = request)
                    proofSigner.await().makeProof(
                        info = info.copy(strength = 10),
                        property = input.property,
                        value = input.value,
                    )
                }
            }
        )

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> established(
        principal: PrincipalType<SUBJECT, ID>,
        subject: SUBJECT,
    ): Boolean =
        modelInfo.table().findOne(
            condition {
                it.subjectId.eq(principal.idString(subject._id)) and
                        it.subjectType.eq(principal.name) and
                        it.usedAt.eq(null)
            }
        ) != null
}