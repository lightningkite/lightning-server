package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.UUID
import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.modelInfo
import com.lightningkite.lightningserver.db.ModelRestEndpoints
import com.lightningkite.lightningserver.db.ModelSerializationInfo
import com.lightningkite.lightningserver.encryption.SecureHasher
import com.lightningkite.lightningserver.encryption.hasher
import com.lightningkite.lightningserver.encryption.secretBasis
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.delete
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.encodeUnwrappingString
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.typed.*
import dev.turingcomplete.kotlinonetimepassword.HmacAlgorithm
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordConfig
import com.lightningkite.now
import com.lightningkite.serialization.DataClassPathSelf
import com.lightningkite.uuid
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import java.security.SecureRandom
import kotlinx.datetime.toJavaInstant
import kotlinx.serialization.builtins.nullable
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@OptIn(InternalSerializationApi::class)
class OneTimePasswordProofEndpoints(
    path: ServerPath,
    val database: () -> Database,
    val cache: () -> Cache,
    val config: TimeBasedOneTimePasswordConfig = TimeBasedOneTimePasswordConfig(
        timeStep = 30,
        timeStepUnit = TimeUnit.SECONDS,
        codeDigits = 6,
        hmacAlgorithm = HmacAlgorithm.SHA1
    ),
    val proofHasher: () -> SecureHasher = secretBasis.hasher("proof"),
) : ServerPathGroup(path), Authentication.DirectProofMethod {
    init {
        path.docName = "OneTimePasswordProof"
    }

    override val info: ProofMethodInfo = ProofMethodInfo(
        via = "otp",
        property = null,
        strength = 5
    )

    init {
        Authentication.register(this)
    }

    private val active get() = condition<OtpSecret> { it.disabledAt.eq(null) and (it.expiresAt.eq(null) or it.expiresAt.notNull.gte(now())) }

    val modelInfo = modelInfo(
        serialization = ModelSerializationInfo<OtpSecret, UUID>(),
        authOptions = anyAuthRoot + Authentication.isAdmin,
        getBaseCollection = { database().collection() },
        forUser = {
            val admin = condition<OtpSecret>(Authentication.isAdmin.accepts(authOrNull))
            val mine = authOrNull?.let { a ->
                condition<OtpSecret> {
                    it.subjectId.eq(a.idString) and it.subjectType.eq(a.subject.name)
                }
            } ?: Condition.Never
            val active = condition<OtpSecret> { it.disabledAt.eq(null) }
            it.withPermissions(
                ModelPermissions(
                    create = Condition.Never,
                    read = admin or mine,
                    readMask = mask {
                        it.secretBase32.mask("")
                    },
                    update = admin or (mine and active),
                    updateRestrictions = updateRestrictions {
                        it.subjectType.cannotBeModified()
                        it.subjectId.cannotBeModified()
                        it.secretBase32.cannotBeModified()
                        it.issuer.cannotBeModified()
                        it.period.cannotBeModified()
                        it.digits.cannotBeModified()
                        it.algorithm.cannotBeModified()
                        it.establishedAt.cannotBeModified()
                    },
                    delete = Condition.Never,
                )
            )
        }
    )

    val rest = ModelRestEndpoints(path("secrets"), modelInfo)

    val establish = path("establish").post.api(
        summary = "Establish an One Time Password",
        inputType = EstablishOtp.serializer(),
        outputType = String.serializer(),
        description = "Generates a new One Time Password configuration.",
        authOptions = anyAuthRoot,
        errorCases = listOf(),
        examples = listOf(),
        implementation = { input: EstablishOtp ->
            modelInfo.collection().updateMany(condition {
                it.subjectId.eq(auth.idString) and it.subjectType.eq(auth.subject.name)
            }, modification {
                it.disabledAt assign now()
                it.secretBase32 assign ""
            })
            val secret = OtpSecret(
                subjectId = auth.idString,
                subjectType = auth.subject.name,
                secret = ByteArray(32).also { SecureRandom.getInstanceStrong().nextBytes(it) },
                label = input.label ?: "",
                issuer = generalSettings().projectName,
                config = config,
            )
            modelInfo.collection().insertOne(secret)
            secret.url
        }
    )

    override val prove = path("prove").post.api(
        authOptions = noAuth,
        summary = "Prove OTP",
        description = "Logs in to the given account with an OTP code.  Limits to 10 attempts per hour.",
        errorCases = listOf(),
        examples = listOf(
            ApiExample(
                input = IdentificationAndPassword(
                    "User",
                    "User/_id",
                    "some-id",
                    "000000"
                ),
                output = Proof(
                    via = info.via,
                    property = "User/_id",
                    strength = info.strength,
                    value = "some-id",
                    at = now(),
                    signature = "opaquesignaturevalue"
                )
            )
        ),
        successCode = HttpStatus.OK,
        implementation = { input: IdentificationAndPassword ->
            val now = now()
            cache().constrainAttemptRate(
                cacheKey = "otp-count-${input.property}-${input.value}"
            ) {
                val subject = input.type
                val handler = Authentication.subjects.values.find { it.name == subject }
                    ?: throw IllegalArgumentException("No subject $subject recognized")
                val subjectId = handler.findUserIdString(input.property, input.value)
                    ?: throw BadRequestException("User ID and code do not match")

                val active = modelInfo.collection().find(condition {
                    it.subjectId.eq(subjectId) and it.subjectType.eq(subject) and active
                }).toList()

                val matching = active.find { it.generator.isValid(input.password, now.toJavaInstant()) }
                    ?: throw BadRequestException("User ID and code do not match")

                modelInfo.collection().updateOneById(matching._id, modification {
                    it.lastUsedAt assign now
                })

                proofHasher().makeProof(
                    info = info,
                    property = input.property,
                    value = input.value,
                    at = now()
                )
            }
        }
    )

    val confirm = path("existing").get.api(
        summary = "Confirm One Time Password",
        inputType = String.serializer(),
        outputType = Unit.serializer(),
        description = "Confirms your OTP, making it fully active",
        authOptions = anyAuthRoot,
        errorCases = listOf(),
        examples = listOf(),
        implementation = { code: String ->
            val active = modelInfo.collection().find(condition {
                it.subjectId.eq(auth.idString) and it.subjectType.eq(auth.subject.name) and it.disabledAt.eq(null)
            }).toList()
            if(active.isEmpty()) throw NotFoundException()
            prove.implementation(
                AuthAndPathParts(null, null, arrayOf()),
                IdentificationAndPassword(
                    auth.subject.name,
                    "${auth.subject.name}/_id",
                    auth.idString.also { println("Confirming info got $it for idstring") },
                    code
                )
            )
            Unit
        }
    )

    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> established(
        handler: Authentication.SubjectHandler<SUBJECT, ID>,
        item: SUBJECT
    ): Boolean {
        @Suppress("UNCHECKED_CAST")
        return modelInfo.collection().count(condition {
            it.subjectId.eq(handler.idString(item._id)) and
                    it.subjectType.eq(handler.name) and
                    active and
                    it.lastUsedAt.neq(null)
        }) > 0
    }
}