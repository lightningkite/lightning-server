package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.UUID
import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.modelInfo
import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.utils.BadWordList
import com.lightningkite.now
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.security.SecureRandom
import kotlin.time.Duration.Companion.seconds

@Serializable
@GenerateDataClassPaths
@IndexSet(["subjectId", "subjectType"])
data class BackupCodeSecret(
    override val _id: UUID = UUID.random(),
    val code: String,
    val subjectId: String,
    val subjectType: String,
) : HasId<UUID>

@OptIn(InternalSerializationApi::class)
class BackupCodeEndpoints(
    path: ServerPath,
    val database: () -> Database,
    val cache: () -> Cache,
    val proofHasher: () -> SecureHasher = secretBasis.hasher("proof"),
    val codeLength: Int = 20,
    val generateCount: Int = 10, // The number of codes to generate
) : ServerPathGroup(path), Authentication.DirectProofMethod {
    init {
        path.docName = "BackupCodeProof"
    }

    override val info: ProofMethodInfo = ProofMethodInfo(
        via = "backupcode",
        property = null,
        strength = 0
    )

    init {
        Authentication.register(this)
    }
    val availableCharacters = ('A'..'Z').toList() - setOf('I', 'O')

    val loggedInInterfaceInfo: Documentable.InterfaceInfo =
        Documentable.InterfaceInfo(path, "AuthenticatedBackupCodeProofClientEndpoints", listOf())
    val interfaceInfo: Documentable.InterfaceInfo =
        Documentable.InterfaceInfo(path, "BackupCodeProofClientEndpoints", listOf())

    private val active
        get() = condition<PasswordSecret> {
            it.disabledAt.eq(null) and (it.expiresAt.eq(null) or it.expiresAt.notNull.gte(
                now()
            ))
        }

    val modelInfo = database.modelInfo<HasId<*>?, BackupCodeSecret, UUID>(
        authOptions = noAuth,
        permissions = { ModelPermissions() }
    )

    val resetCodes = path("reset-codes").post.api<HasId<*>, Unit, List<String>>(
        belongsToInterface = loggedInInterfaceInfo,
        summary = "Reset Codes",
        inputType = Unit.serializer(),
        outputType = ListSerializer(String.serializer()),
        description = "Reset your existing backup codes with new ones. Input how many codes you wish to generate",
        authOptions = anyAuthRoot,
        errorCases = listOf(),
        examples = listOf(),
        implementation = { _: Unit ->

            modelInfo.collection().deleteManyIgnoringOld(
                condition { it.subjectId.eq(auth.idString) and it.subjectType.eq(auth.subject.name) }
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

            modelInfo.collection().insert(newCodes.map {
                BackupCodeSecret(
                    code = it.lowercase(),
                    subjectId = auth.idString,
                    subjectType = auth.subject.name,
                )
            })

            newCodes.map { code -> code.chunked(5).joinToString("-") }
        }
    )

    val clearCodes = path("clear-codes").post.api<HasId<*>, Unit, Unit>(
        belongsToInterface = loggedInInterfaceInfo,
        summary = "Clear Codes",
        inputType = Unit.serializer(),
        outputType = Unit.serializer(),
        description = "Removes all backup codes for the user",
        authOptions = anyAuthRoot,
        errorCases = listOf(),
        examples = listOf(),
        implementation = { _: Unit ->

            modelInfo.collection().deleteManyIgnoringOld(
                condition { it.subjectId.eq(auth.idString) and it.subjectType.eq(auth.subject.name) }
            )

            Unit
        }
    )

    val established = path("established").get.api<HasId<*>, Unit, Boolean>(
        belongsToInterface = loggedInInterfaceInfo,
        summary = "Established",
        inputType = Unit.serializer(),
        outputType = Boolean.serializer(),
        description = "Returns whether or a user has valid backup codes established",
        authOptions = anyAuthRoot,
        errorCases = listOf(),
        examples = listOf(),
        implementation = { _: Unit ->

            modelInfo.collection().findOne(
                condition { it.subjectId.eq(auth.idString) and it.subjectType.eq(auth.subject.name) }
            ) != null
        }
    )

    override val prove = path("prove").post.api(
        belongsToInterface = interfaceInfo,
        authOptions = noAuth,
        summary = "Prove With Backup Code",
        description = "Use an established backup code as an authentication method.",
        errorCases = listOf(),
        examples = listOf(
            ApiExample(
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
                    at = now(),
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

                val handler = Authentication.subjects.values.find { it.name == subject }
                    ?: throw IllegalArgumentException("No subject $subject recognized")

                val subjectId = handler.findUserIdString(input.property, input.value)
                    ?: throw BadRequestException("Invalid Backup Code")

                val secrets = modelInfo.collection().find(condition {
                    it.subjectId.eq(subjectId) and
                            it.subjectType.eq(subject)
                })
                    .toList()

                val normalizedCode = input.password.filter { it.isLetter() }.lowercase()
                val match = secrets.find { normalizedCode == it.code }
                    ?: throw BadRequestException("Invalid Backup Code")

                modelInfo.collection().deleteOneById(match._id)

                proofHasher().makeProof(
                    info = info.copy(strength = 10),
                    property = input.property,
                    value = input.value,
                    at = now()
                )
            }
        }
    )

    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> established(
        handler: Authentication.SubjectHandler<SUBJECT, ID>,
        item: SUBJECT,
    ): Boolean = modelInfo.collection()
        .findOne(condition {
            it.subjectId.eq(handler.idString(item._id)) and
                    it.subjectType.eq(handler.name)
        }) != null
}