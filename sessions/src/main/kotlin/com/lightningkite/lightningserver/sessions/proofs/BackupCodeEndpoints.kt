package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.sessions.proofs.extensions.constrainAttemptRate
import com.lightningkite.lightningserver.auth.fetchUserIdString
import com.lightningkite.lightningserver.auth.idString
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.sessions.proofs.extensions.makeProof
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.explicitApiHttpHandler
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.lightningserver.typed.auth
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.lightningserver.typed.sdk.SdkModule
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.lightningserver.typed.sdk.clientInterface
import com.lightningkite.lightningserver.typed.sdk.info
import com.lightningkite.lightningserver.typed.sdk.sdkSettings
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
import kotlin.uuid.Uuid

@Serializable
@GenerateDataClassPaths
@IndexSet(["subjectId", "subjectType"])
public data class BackupCodeSecret(
    override val _id: Uuid = Uuid.random(),
    val code: String,
    val subjectId: String,
    val subjectType: String,
) : HasId<Uuid>

@OptIn(InternalSerializationApi::class)
public class BackupCodeEndpoints(
    database: Runtime<Database>,
    private val cache: Runtime<Cache>,
    private val proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proof"),
    private val codeLength: Int = 20,
    private val generateCount: Int = 10, // The number of codes to generate
) : ServerBuilder(), DirectProofMethod {

    init {
        proofMethods.register(this)

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
            permissions = { ModelPermissions<BackupCodeSecret>() }
        )

    public val resetCodes: ApiHttpHandler<PathSpec0, HasId<AnyId>, Unit, List<String>> =
        path.path("reset-codes").post bind explicitApiHttpHandler(
            summary = "Reset Codes",
            inputType = Unit.serializer(),
            outputType = ListSerializer(String.serializer()),
            description = "Reset your existing backup codes with new ones. Input how many codes you wish to generate",
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
                        code = it.lowercase(),
                        subjectId = auth.rawId,
                        subjectType = auth.principalName,
                    )
                })

                newCodes.map { code -> code.chunked(5).joinToString("-") }
            }
        )

    public val clearCodes: ApiHttpHandler<PathSpec0, HasId<AnyId>, Unit, Unit> =
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

                Unit
            }
        )

    public val established: ApiHttpHandler<PathSpec0, HasId<AnyId>, Unit, Boolean> =
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
                    condition { it.subjectId.eq(auth.rawId) and it.subjectType.eq(auth.principalName) }
                ) != null
            }
        )

    public override val prove: ApiHttpHandler<PathSpec0, HasId<AnyId>?, IdentificationAndPassword, Proof> =
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
                        signature = "opaquesignaturevalue"
                    )
                )
            ),
            successCode = HttpStatus.OK,
            implementation = { input: IdentificationAndPassword ->
                cache().constrainAttemptRate(
                    cacheKey = "backup-code-count-${input.property}-${input.value}"
                ) {
                    val subject = input.type

                    val handler = serverRuntime.server.principalTypes.values.find { it.name == subject }
                        ?: throw IllegalArgumentException("No subject $subject recognized")

                    val subjectId = handler.fetchUserIdString(input.property, input.value)
                        ?: throw BadRequestException("Invalid Backup Code")

                    val secrets = modelInfo.table().find(condition {
                        it.subjectId.eq(subjectId) and
                                it.subjectType.eq(subject)
                    })
                        .toList()

                    val normalizedCode = input.password.filter { it.isLetter() }.lowercase()
                    val match = secrets.find { normalizedCode == it.code }
                        ?: throw BadRequestException("Invalid Backup Code")

                    modelInfo.table().deleteOneById(match._id)

                    proofSigner.await().makeProof(
                        info = info.copy(strength = 10),
                        property = input.property,
                        value = input.value,
                        at = now()
                    )
                }
            }
        )

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> established(
        principal: PrincipalType<SUBJECT, ID>,
        subject: SUBJECT,
    ): Boolean = modelInfo.table()
        .findOne(condition {
            it.subjectId.eq(principal.idString(subject._id)) and
                    it.subjectType.eq(principal.name)
        }) != null
}