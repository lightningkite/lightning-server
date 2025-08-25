package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.builder.bind
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.runtime.test.serverRuntime
import com.lightningkite.lightningserver.sessions.*
import com.lightningkite.lightningserver.sessions.proofs.extensions.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.*
import dev.turingcomplete.kotlinonetimepassword.HmacAlgorithm
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordConfig
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.builtins.serializer
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid


@OptIn(InternalSerializationApi::class)
public class TimeBasedOTPProofEndpoints(
    database: Runtime<Database>,
    private val cache: Runtime<Cache>,
    private val proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proof"),
    private val config: TimeBasedOneTimePasswordConfig = TimeBasedOneTimePasswordConfig(
        timeStep = 30,
        timeStepUnit = TimeUnit.SECONDS,
        codeDigits = 6,
        hmacAlgorithm = HmacAlgorithm.SHA1
    ),
) : ServerBuilder(), DirectProofMethod {

    init {
        proofMethods.register(this)
    }

    override val info: ProofMethodInfo = ProofMethodInfo(
        via = "totp",
        property = null,
        strength = 10
    )

    context(_: ServerRuntime)
    private val active
        get() = condition<TotpSecret> {
            it.disabledAt.eq(null) and (it.expiresAt.eq(null) or it.expiresAt.notNull.gte(now()))
        }

    public val modelInfo: ModelInfo<HasId<AnyId>, AnyId, TotpSecret, Uuid> = database.modelInfo(
        authOptions = recentRootAuth or AuthRequirement.IsAdmin,
        permissions = {
            val admin = condition<TotpSecret>(AuthRequirement.IsAdmin.accepts(authOrNull))
            val mine = authOrNull?.let { a ->
                condition<TotpSecret> {
                    it.subjectId.eq(a.rawId) and it.subjectType.eq(a.principalName)
                }
            } ?: Condition.Never
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
        }
    )

    public val rest: ModelRestEndpoints<HasId<AnyId>, AnyId, TotpSecret, Uuid> = ModelRestEndpoints(modelInfo)

    public val establish: Locationed<HttpEndpoint<PathSpec0>, ApiHttpHandler<PathSpec0, HasId<AnyId>, AnyId, EstablishOtp, String>> =
        path.path("establish").post bind ApiHttpHandler(
            summary = "Establish One Time Password", // Version 5: Rename to "Establish Time Based One Time Password"
            inputType = EstablishOtp.serializer(),
            outputType = String.serializer(),
            description = "Generates a new Time Based One Time Password configuration.",
            authOptions = recentRootAuth,
            errorCases = listOf(),
            examples = listOf(),
            handler = { input: EstablishOtp ->
                modelInfo.collection().updateMany(condition {
                    it.subjectId.eq(auth.rawId) and it.subjectType.eq(auth.principalName)
                }, modification {
                    it.disabledAt assign now()
                    it.secretBase32 assign ""
                })
                val secret = TotpSecret(
                    subjectId = auth.rawId,
                    subjectType = auth.principalName,
                    secret = ByteArray(32).also { SecureRandom.getInstanceStrong().nextBytes(it) },
                    label = input.label ?: "",
                    issuer = generalSettings().projectName,
                    config = config,
                )
                modelInfo.collection().insertOne(secret)
                secret.url
            }
        )

    override val prove: Locationed<HttpEndpoint<PathSpec0>, ApiHttpHandler<PathSpec0, HasId<AnyId>?, AnyId, IdentificationAndPassword, Proof>> =
        path.path("prove").post bind ApiHttpHandler(
            authOptions = noAuth,
            summary = "Prove OTP", // Version 5: Rename to "Prove TOTP"
            description = "Logs in to the given account with an TOTP code.  Limits to 10 attempts per hour.",
            errorCases = listOf(),
            examples = listOf(
                ApiHttpHandler.Example(
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
                        at = Clock.System.now(),
                        signature = "opaquesignaturevalue"
                    )
                )
            ),
            successCode = HttpStatus.OK,
            handler = { input: IdentificationAndPassword ->
                val now = now()
                cache().constrainAttemptRate(
                    cacheKey = "totp-count-${input.property}-${input.value}"
                ) {
                    val subject = input.type
                    val handler = serverRuntime.server.principalTypes.values.find { it.name == subject }
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

                    proofSigner.await().makeProof(
                        info = info,
                        property = input.property,
                        value = input.value,
                        at = now()
                    )
                }
            }
        )

    public val confirm: Locationed<HttpEndpoint<PathSpec0>, ApiHttpHandler<PathSpec0, HasId<AnyId>, AnyId, String, Unit>> =
        path.path("existing").post bind ApiHttpHandler(
            summary = "Confirm One Time Password", // Version 5: Rename to "Confirm Time Based One Time Password"
            inputType = String.serializer(),
            outputType = Unit.serializer(),
            description = "Confirms your TOTP, making it fully active",
            authOptions = recentRootAuth,
            errorCases = listOf(),
            examples = listOf(),
            handler = { code: String ->
                val active = modelInfo.collection().find(condition {
                    it.subjectId.eq(auth.rawId) and it.subjectType.eq(auth.principalName) and it.disabledAt.eq(null)
                }).toList()

                if (active.isEmpty()) throw NotFoundException()

                val proveAccess = this.request.access(prove.item.authOptions)
                prove.item.handle(
                    proveAccess,
                    IdentificationAndPassword(
                        type = auth.principalName,
                        property = "${auth.principalName}/_id",
                        value = auth.rawId,
                        password = code
                    )
                )

                Unit
            }
        )

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<AnyId>> established(
        handler: PrincipalType<SUBJECT, AnyId>,
        item: SUBJECT,
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