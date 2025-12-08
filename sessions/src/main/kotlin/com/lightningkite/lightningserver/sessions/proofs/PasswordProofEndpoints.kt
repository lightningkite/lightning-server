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
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.sessions.*
import com.lightningkite.lightningserver.sessions.proofs.extensions.constrainAttemptRate
import com.lightningkite.lightningserver.auth.fetchUserIdString
import com.lightningkite.lightningserver.auth.idString
import com.lightningkite.lightningserver.encryption.checkAgainstHash
import com.lightningkite.lightningserver.encryption.secureHash
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.sessions.proofs.extensions.makeProof
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.sdk.SdkModule
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.lightningserver.typed.sdk.clientInterface
import com.lightningkite.lightningserver.typed.sdk.info
import com.lightningkite.lightningserver.typed.sdk.sdkSettings
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.builtins.serializer
import kotlin.time.Clock
import kotlin.uuid.Uuid

@OptIn(InternalSerializationApi::class)
public class PasswordProofEndpoints(
    database: Runtime<Database>,
    private val cache: Runtime<Cache>,
    private val proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proof"),
    private val evaluatePassword: (String) -> Unit = { },
) : ServerBuilder(), DirectProofMethod {

    init {
        proofMethods.register(this)

        sdkSettings.defaultInfo = SdkModule.Info("PasswordProof", "password")
        sdkSettings.clientInterface = ProofClientEndpoints.Password::class.info()
    }

    override val info: ProofMethodInfo = ProofMethodInfo(
        via = "password",
        property = null,
        strength = 10
    )

    context(_: ServerRuntime)
    private val active
        get() = condition<PasswordSecret> {
            it.disabledAt.eq(null) and ((it.expiresAt.eq(null) or it.expiresAt.notNull.gt(now())))
        }

    public val modelInfo: ModelInfo<HasId<*>, PasswordSecret, Uuid> =
        database.modelInfo(
            auth = proofMethodAuth or AuthRequirement.IsAdmin,
            signals = { col ->
                col.interceptCreate {
                    evaluatePassword(it.hash)
                    if (it.hint?.contains(it.hash, true) == true)
                        throw BadRequestException("Hint cannot contain the password itself!")
                    it.copy(hash = it.hash.secureHash())
                }
            },
            permissions = {
                val admin = condition<PasswordSecret>(AuthRequirement.IsAdmin.accepts(authOrNull))
                val mine = authOrNull?.let { a ->
                    condition<PasswordSecret> {
                        it.subjectId.eq(a.rawId) and it.subjectType.eq(a.principalName)
                    }
                } ?: Condition.Never
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
            }
        )

    public val rest: ModelRestEndpoints<HasId<*>, PasswordSecret, Uuid> =
        path.path("secrets") include ModelRestEndpoints(modelInfo)

    context(_: ServerRuntime)
    public suspend fun <SUBJECT : HasId<ID>, ID: Comparable<ID>> establish(
        subject: PrincipalType<SUBJECT, ID>,
        id: ID,
        password: EstablishPassword,
    ) {
        val now = now()
        val secret = PasswordSecret(
            subjectId = subject.idString(id),
            subjectType = subject.name,
            hash = password.password,
            hint = password.hint,
            establishedAt = now
        )
        modelInfo.table().updateMany(
            condition {
                Condition.And(
                    it.subjectId eq secret.subjectId,
                    it.subjectType eq secret.subjectType,
                    it.establishedAt lt now
                )
            },
            modification { it.disabledAt assign now }
        )
        modelInfo.table().insertOne(secret)
    }

    public val establish: ApiHttpHandler<PathSpec0, HasId<*>, EstablishPassword, Unit> =
        path.path("establish").post bind explicitApiHttpHandler(
            summary = "Establish Password",
            inputType = EstablishPassword.serializer(),
            outputType = Unit.serializer(),
            description = "Set your password",
            auth = proofMethodAuth,
            errorCases = emptyList(),
            implementation = { value: EstablishPassword ->
                @Suppress("UNCHECKED_CAST")
                establish(
                    auth.untypedPrincipal as PrincipalType<HasId<Comparable<Any?>>, Comparable<Any?>>,
                    auth.untypedId as Comparable<Any?>,
                    value
                )
                Unit
            }
        )

    public override val prove: ApiHttpHandler<PathSpec0, HasId<*>?, IdentificationAndPassword, Proof> =
        path.path("prove").post bind ApiHttpHandler(
            auth = noAuth,
            summary = "Prove password ownership",
            description = "Logs in to the given account with a password.",
            errorCases = listOf(),
            examples = listOf(
                ApiHttpHandler.Example(
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
                        at = Clock.System.now(),
                        signature = "opaquesignaturevalue"
                    )
                )
            ),
            successCode = HttpStatus.OK,
            implementation = { input: IdentificationAndPassword ->
                val now = now()
                cache().constrainAttemptRate("password-${input.property}-${input.value}") {
                    val subject = input.type
                    val handler = serverRuntime.server.principalTypes.values.find { it.name == subject }
                        ?: throw IllegalArgumentException("No subject $subject recognized")
                    val subjectId = handler.fetchUserIdString(input.property, input.value)
                        ?: throw BadRequestException("User ID and code do not match")

                    val active = modelInfo.table().find(condition {
                        it.subjectId.eq(subjectId) and it.subjectType.eq(subject) and active
                    }).toList()

                    val matching = active.find { input.password.checkAgainstHash(it.hash) }
                        ?: throw BadRequestException("User ID and code do not match")

                    modelInfo.table().updateOneById(matching._id, modification {
                        it.lastUsedAt assign now
                    })

                    proofSigner.await().makeProof(
                        info = info,
                        property = input.property,
                        value = input.value,
                        at = now()
                    )
                }
            }
        )

    context(server: ServerRuntime)
    public override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> established(
        principal: PrincipalType<SUBJECT, ID>,
        subject: SUBJECT,
    ): Boolean {
        @Suppress("UNCHECKED_CAST")
        return modelInfo.table().count(condition {
            Condition.And(
                it.subjectId eq principal.idString(subject._id),
                it.subjectType eq principal.name,
                active
            )
        }) > 0
    }
}