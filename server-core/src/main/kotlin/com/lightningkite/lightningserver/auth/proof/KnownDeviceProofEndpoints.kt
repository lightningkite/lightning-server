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
import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.http.*
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@OptIn(InternalSerializationApi::class)
class KnownDeviceProofEndpoints(
    path: ServerPath,
    val database: () -> Database,
    val cache: () -> Cache,
    val expires: () -> Duration = { 30.days },
    val proofHasher: () -> SecureHasher = secretBasis.hasher("proof"),
) : ServerPathGroup(path), Authentication.StringProofMethod {
    init {
        path.docName = "OneTimePasswordProof"
    }

    override val info: ProofMethodInfo = ProofMethodInfo(
        via = "known-device",
        property = null,
        strength = 3
    )

    init {
        Authentication.register(this)
    }

    private val active get() = condition<KnownDeviceSecret> { it.disabledAt.eq(null) and (it.expiresAt.eq(null) or it.expiresAt.notNull.gte(now())) }

    val modelInfo = modelInfo(
        serialization = ModelSerializationInfo<KnownDeviceSecret, UUID>(),
        authOptions = anyAuthRoot + Authentication.isAdmin,
        getBaseCollection = { database().collection() },
        getCollection = {
            it.interceptCreate {
                it.copy(hash = it.hash.secureHash(), expiresAt = now() + expires())
            }
        },
        forUser = {
            val admin = condition<KnownDeviceSecret>(Authentication.isAdmin.accepts(authOrNull))
            val mine = authOrNull?.let { a ->
                condition<KnownDeviceSecret> {
                    it.subjectId.eq(a.idString) and it.subjectType.eq(a.subject.name)
                }
            } ?: Condition.Never
            val active = condition<KnownDeviceSecret> { it.disabledAt.eq(null) }
            it.withPermissions(
                ModelPermissions(
                    create = Condition.Never,
                    read = admin or mine,
                    readMask = mask {
                        it.hash.mask("")
                    },
                    update = admin or (mine and active),
                    updateRestrictions = updateRestrictions {
                        it.subjectType.cannotBeModified()
                        it.subjectId.cannotBeModified()
                        it.hash.cannotBeModified()
                        it.deviceInfo.cannotBeModified()
                        it.establishedAt.cannotBeModified()
                    },
                    delete = Condition.Never,
                )
            )
        }
    )

    val rest = ModelRestEndpoints(path("secrets"), modelInfo)

    suspend fun <T : HasId<ID>, ID : Comparable<ID>> establish(
        subject: Authentication.SubjectHandler<T, ID>,
        id: ID,
        deviceInfo: String
    ): String {
        val secretValue = uuid().toString()
        val secretId = uuid()
        @Suppress("UNCHECKED_CAST")
        val secret = KnownDeviceSecret(
            _id = secretId,
            hash = secretValue.secureHash(),
            subjectId = subject.idString(id),
            subjectType = subject.name,
            deviceInfo = deviceInfo
        )
        modelInfo.collection().insertOne(secret)
        return "$secretId/$secretValue"
    }

    val establish = path("establish").post.api(
        summary = "Establish Known Device",
        inputType = Unit.serializer(),
        outputType = String.serializer(),
        description = "Establishes a new known device.  You can use the returned string to gain partial authentication later.",
        authOptions = anyAuthRoot,
        errorCases = listOf(),
        examples = listOf(),
        implementation = { value: Unit ->
            @Suppress("UNCHECKED_CAST")
            establish(
                auth.subject as Authentication.SubjectHandler<HasId<Comparable<Comparable<*>>>, Comparable<Comparable<*>>>,
                auth.rawId as Comparable<Comparable<*>>,
                run {
                    val agent = rawRequest?.headers?.get(HttpHeader.UserAgent)
                    val ip = rawRequest?.sourceIp
                    "$agent / $ip"
                }
            )
        }
    )

    override val prove = path("prove").post.api(
        authOptions = noAuth,
        summary = "Prove Known Device",
        description = "Get proof that your device is known.",
        errorCases = listOf(),
        examples = listOf(
            ApiExample(
                input = "${uuid()}/${uuid()}",
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
        implementation = { input: String ->
            val now = now()
            val id = input.substringBefore('/').let { uuid(it) }
            val secret = input.substringAfter('/')
            cache().constrainAttemptRate(
                cacheKey = "known-devices-count-${id}"
            ) {
                val active = modelInfo.collection().get(id)
                    ?: throw BadRequestException("No such known device")

                if(!secret.checkAgainstHash(active.hash))
                    throw BadRequestException("User ID and code do not match")

                modelInfo.collection().updateOneById(id, modification {
                    it.lastUsedAt assign now
                })

                proofHasher().makeProof(
                    info = info,
                    property = "${active.subjectType}/_id",
                    value = active.subjectId,
                    at = now()
                )
            }
        }
    )

    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> established(
        handler: Authentication.SubjectHandler<SUBJECT, ID>,
        item: SUBJECT
    ): Boolean  = false
}