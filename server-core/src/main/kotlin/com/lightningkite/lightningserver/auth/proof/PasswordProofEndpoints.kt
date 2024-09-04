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
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.delete
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.decodeUnwrappingString
import com.lightningkite.lightningserver.serialization.encodeUnwrappingString
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.now
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.toJavaInstant
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@OptIn(InternalSerializationApi::class)
class PasswordProofEndpoints(
    path: ServerPath,
    val database: () -> Database,
    val cache: () -> Cache,
    val proofHasher: () -> SecureHasher = secretBasis.hasher("proof"),
    val evaluatePassword: (String) -> Unit = { }
) : ServerPathGroup(path), Authentication.DirectProofMethod {
    init {
        path.docName = "PasswordProof"
    }

    override val info: ProofMethodInfo = ProofMethodInfo(
        via = "password",
        property = null,
        strength = 10
    )

    init {
        Authentication.register(this)
    }

    val loggedInInterfaceInfo: Documentable.InterfaceInfo = Documentable.InterfaceInfo(path, "AuthenticatedPasswordProofClientEndpoints", listOf())
    val interfaceInfo: Documentable.InterfaceInfo = Documentable.InterfaceInfo(path, "PasswordProofClientEndpoints", listOf())

    private val active get() = condition<PasswordSecret> { it.disabledAt.eq(null) and (it.expiresAt.eq(null) or it.expiresAt.notNull.gte(now())) }

    val modelInfo = modelInfo(
        serialization = ModelSerializationInfo<PasswordSecret, UUID>(),
        authOptions = anyAuthRoot + Authentication.isAdmin,
        getBaseCollection = { database().collection() },
        getCollection = {
            it.interceptCreate {
                evaluatePassword(it.hash)
                if (it.hint?.contains(it.hash, true) == true)
                    throw BadRequestException("Hint cannot contain the password itself!")
                it.copy(hash = it.hash.secureHash())
            }
        },
        forUser = {
            val admin = condition<PasswordSecret>(Authentication.isAdmin.accepts(authOrNull))
            val mine = authOrNull?.let { a ->
                condition<PasswordSecret> {
                    it.subjectId.eq(a.idString) and it.subjectType.eq(a.subject.name)
                }
            } ?: Condition.Never
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
        password: EstablishPassword
    ) {
        val now = now()
        val secret = PasswordSecret(
            subjectId = subject.idString(id),
            subjectType = subject.name,
            hash = password.password.secureHash(),
            hint = password.hint,
        )
        modelInfo.collection().insertOne(secret)
        modelInfo.collection().updateMany(condition {
            it.subjectId.eq(secret.subjectId) and it.subjectType.eq(secret.subjectType) and it.establishedAt.lt(now)
        }, modification {
            it.disabledAt assign now
        })
    }

    val establish = path("establish").post.api<HasId<*>, EstablishPassword, Unit>(
        belongsToInterface = loggedInInterfaceInfo,
        summary = "Establish Password",
        inputType = EstablishPassword.serializer(),
        outputType = Unit.serializer(),
        description = "Set your password",
        authOptions = anyAuthRoot,
        errorCases = listOf(),
        examples = listOf(),
        implementation = { value: EstablishPassword ->
            @Suppress("UNCHECKED_CAST")
            establish(
                auth.subject as Authentication.SubjectHandler<HasId<Comparable<Comparable<*>>>, Comparable<Comparable<*>>>,
                auth.rawId as Comparable<Comparable<*>>,
                value
            )
            Unit
        }
    )

    override val prove = path("prove").post.api(
        belongsToInterface = interfaceInfo,
        authOptions = noAuth,
        summary = "Prove password ownership",
        description = "Logs in to the given account with a password.",
        errorCases = listOf(),
        examples = listOf(
            ApiExample(
                input = IdentificationAndPassword(
                    "User",
                    "email",
                    "test@test.com",
                    "password"
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
            val now = now()
            cache().constrainAttemptRate("password-${input.property}-${input.value}") {
                val subject = input.type
                val handler = Authentication.subjects.values.find { it.name == subject }
                    ?: throw IllegalArgumentException("No subject $subject recognized")
                val subjectId = handler.findUserIdString(input.property, input.value)
                    ?: throw BadRequestException("User ID and code do not match")

                val active = modelInfo.collection().find(condition {
                    it.subjectId.eq(subjectId) and it.subjectType.eq(subject) and active
                }).toList()

                val matching = active.find { input.password.checkAgainstHash(it.hash) }
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

    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> established(
        handler: Authentication.SubjectHandler<SUBJECT, ID>,
        item: SUBJECT
    ): Boolean {
        @Suppress("UNCHECKED_CAST")
        return modelInfo.collection().count(condition {
            it.subjectId.eq(handler.idString(item._id)) and
                    it.subjectType.eq(handler.name) and
                    active
        }) > 0
    }
}