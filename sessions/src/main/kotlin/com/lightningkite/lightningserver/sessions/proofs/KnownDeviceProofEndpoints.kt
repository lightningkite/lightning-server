package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.builder.bind
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.sessions.*
import com.lightningkite.lightningserver.sessions.proofs.extensions.constrainAttemptRate
import com.lightningkite.lightningserver.auth.idString
import com.lightningkite.lightningserver.sessions.proofs.extensions.makeProof
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.*
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.builtins.serializer
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

@OptIn(InternalSerializationApi::class)
public class KnownDeviceProofEndpoints(
    database: Runtime<Database>,
    private val cache: Runtime<Cache>,
    private val proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proof"),
    private val expires: () -> Duration = { 30.days },
) : ServerBuilder(), StringProofMethod {

    init {
        proofMethods.register(this)
    }

    override val info: ProofMethodInfo = ProofMethodInfo(
        via = "known-device",
        property = null,
        strength = 3
    )

    context(_: ServerRuntime)
    private val active
        get() = condition<KnownDeviceSecret> {
            it.disabledAt.eq(null) and ((it.expiresAt.eq(null) or it.expiresAt.notNull.gte(now())))
        }

    public val modelInfo: ModelInfo<HasId<AnyId>, KnownDeviceSecret, Uuid> = database.modelInfo(
        auth = recentRootAuth or AuthRequirement.isAdmin,
        signals = {
            it.interceptCreate {
                it.copy(hash = it.hash.secureHash(), expiresAt = now() + expires())
            }
        },
        permissions = {
            val admin = condition<KnownDeviceSecret>(AuthRequirement.IsAdmin.accepts(authOrNull))
            val mine = authOrNull?.let { a ->
                condition<KnownDeviceSecret> {
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
                    it.deviceInfo.cannotBeModified()
                    it.establishedAt.cannotBeModified()
                },
                delete = Condition.Never,
            )
        }
    )

    public val rest: ModelRestEndpoints<HasId<AnyId>, KnownDeviceSecret, Uuid> = ModelRestEndpoints(modelInfo)

    context(_: ServerRuntime)
    public suspend fun <SUBJECT : HasId<ID>, ID: Comparable<ID>> establish(
        principal: PrincipalType<SUBJECT, ID>,
        id: ID,
        deviceInfo: String,
    ): KnownDeviceSecretAndExpiration {
        val secretValue = Uuid.random().toString()
        val secretId = Uuid.random()
        val exp = now() + expires()

        val secret = KnownDeviceSecret(
            _id = secretId,
            hash = secretValue.secureHash(),
            subjectId = principal.idString(id),
            subjectType = principal.name,
            deviceInfo = deviceInfo,
            establishedAt = now()
        )
        modelInfo.collection().insertOne(secret)
        return KnownDeviceSecretAndExpiration("$secretId/$secretValue", exp)
    }

    public val establish: Locationed<HttpEndpoint<PathSpec0>, ApiHttpHandler<PathSpec0, HasId<AnyId>, Unit, String>> =
        path.path("establish").post bind ApiHttpHandler(
            summary = "Establish Known Device",
            inputType = Unit.serializer(),
            outputType = String.serializer(),
            description = "Establishes a new known device.  You can use the returned string to gain partial authentication later.",
            auth = recentRootAuth,
            errorCases = listOf(),
            examples = listOf(),
            handler = { _: Unit ->
                establish(
                    auth.principalType,
                    auth.id,
                    run {
                        val agent = request.headers[HttpHeader.UserAgent]
                        val ip = request.sourceIp
                        "$agent / $ip"
                    }
                ).secret
            }
        )

    public val establish2: Locationed<HttpEndpoint<PathSpec0>, ApiHttpHandler<PathSpec0, HasId<AnyId>, Unit, KnownDeviceSecretAndExpiration>> =
        path.path("establish2").post bind ApiHttpHandler(
            summary = "Establish Known Device V2",
            inputType = Unit.serializer(),
            outputType = KnownDeviceSecretAndExpiration.serializer(),
            description = "Establishes a new known device.  You can use the returned string to gain partial authentication later.",
            auth = recentRootAuth,
            errorCases = listOf(),
            examples = listOf(),
            handler = { _: Unit ->
                establish(
                    auth.principalType,
                    auth.id,
                    run {
                        val agent = request.headers[HttpHeader.UserAgent]
                        val ip = request.sourceIp
                        "$agent / $ip"
                    }
                )
            }
        )

    public val options: Locationed<HttpEndpoint<PathSpec0>, ApiHttpHandler<PathSpec0, HasId<AnyId>?, Unit, KnownDeviceOptions>> =
        path.path("options").get bind ApiHttpHandler(
            summary = "Known Device Options",
            inputType = Unit.serializer(),
            outputType = KnownDeviceOptions.serializer(),
            description = "Gives information about how valuable working from a known device is and for how long it works.",
            auth = noAuth,
            errorCases = listOf(),
            examples = listOf(),
            handler = { _: Unit ->
                KnownDeviceOptions(
                    duration = expires(),
                    strength = info.strength
                )
            }
        )

    public override val prove: Locationed<HttpEndpoint<PathSpec0>, ApiHttpHandler<PathSpec0, HasId<AnyId>?, String, Proof>> =
        path.path("prove").post bind ApiHttpHandler(
            auth = noAuth,
            summary = "Prove Known Device",
            description = "Get proof that your device is known.",
            errorCases = listOf(),
            examples = listOf(
                ApiHttpHandler.Example(
                    input = "${Uuid.random()}/${Uuid.random()}",
                    output = Proof(
                        via = info.via,
                        property = "User/_id",
                        strength = info.strength,
                        value = "some-id",
                        at = Clock.System.now(),
                        signature = "opaquesignaturevalue"
                    )
                )
            ),
            successCode = HttpStatus.OK,
            handler = { input: String ->
                val now = now()
                val id = input.substringBefore('/').let { Uuid.parse(it) }
                val secret = input.substringAfter('/')
                cache().constrainAttemptRate(
                    cacheKey = "known-devices-count-${id}"
                ) {
                    val active = modelInfo.collection().get(id)
                        ?: throw BadRequestException("No such known device")

                    if (!secret.checkAgainstHash(active.hash))
                        throw BadRequestException("User ID and code do not match")

                    modelInfo.collection().updateOneById(id, modification {
                        it.lastUsedAt assign now
                    })

                    proofSigner.await().makeProof(
                        info = info,
                        property = "${active.subjectType}/_id",
                        value = active.subjectId,
                        at = now()
                    )
                }
            }
        )

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<AnyId>> established(
        principal: PrincipalType<SUBJECT, AnyId>,
        item: SUBJECT,
    ): Boolean = false
}