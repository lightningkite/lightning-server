package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.sessions.*
import com.lightningkite.lightningserver.sessions.proofs.extensions.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.sdk.SdkModule
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.lightningserver.typed.sdk.clientInterface
import com.lightningkite.lightningserver.typed.sdk.info
import com.lightningkite.lightningserver.typed.sdk.sdkSettings
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

        sdkSettings.defaultInfo = SdkModule.Info("TimeBasedOTPProof", "totp")
        sdkSettings.clientInterface = ProofClientEndpoints.TimeBasedOTP::class.info()
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

    public val modelInfo: ModelInfo<HasId<AnyId>, TotpSecret, Uuid> = database.modelInfo(
        auth = proofMethodAuth or AuthRequirement.IsAdmin,
        permissions = {
            val admin = condition<TotpSecret>(AuthRequirement.IsAdmin.accepts(authOrNull))
            val mine = condition<TotpSecret> {
                it.subjectId.eq(auth.rawId) and it.subjectType.eq(auth.principalName)
            }
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

    public val rest: ModelRestEndpoints<HasId<AnyId>, TotpSecret, Uuid> = path.path("secrets") include ModelRestEndpoints(modelInfo)

    public val establish: ApiHttpHandler<PathSpec0, HasId<AnyId>, EstablishOtp, String> =
        path.path("establish").post bind ApiHttpHandler(
            summary = "Establish Time Based One Time Password",
            inputType = EstablishOtp.serializer(),
            outputType = String.serializer(),
            description = "Generates a new Time Based One Time Password configuration.",
            auth = proofMethodAuth,
            errorCases = listOf(),
            examples = listOf(),
            implementation = { input: EstablishOtp ->
                modelInfo.table().updateMany(condition {
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
                modelInfo.table().insertOne(secret)
                secret.url
            }
        )

    override val prove: ApiHttpHandler<PathSpec0, HasId<AnyId>?, IdentificationAndPassword, Proof> =
        path.path("prove").post bind ApiHttpHandler(
            auth = noAuth,
            summary = "Prove TOTP",
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
            implementation = { input: IdentificationAndPassword ->
                val now = now()
                cache().constrainAttemptRate(
                    cacheKey = "totp-count-${input.property}-${input.value}"
                ) {
                    val subject = input.type
                    val handler = serverRuntime.server.principalTypes[subject]
                        ?: throw IllegalArgumentException("No subject $subject recognized")
                    val subjectId = handler.fetchUserIdString(input.property, input.value)
                        ?: throw BadRequestException("User ID and code do not match")

                    val active = modelInfo.table().find(condition {
                        it.subjectId.eq(subjectId) and it.subjectType.eq(subject) and active
                    }).toList()

                    val matching = active.find { it.generator.isValid(input.password, now.toJavaInstant()) }
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

    public val confirm: ApiHttpHandler<PathSpec0, HasId<AnyId>, String, Unit> =
        path.path("existing").post bind ApiHttpHandler(
            summary = "Confirm Time Based One Time Password",
            inputType = String.serializer(),
            outputType = Unit.serializer(),
            description = "Confirms your TOTP, making it fully active",
            auth = proofMethodAuth,
            errorCases = listOf(),
            examples = listOf(),
            implementation = { code: String ->
                val active = modelInfo.table().find(condition {
                    it.subjectId.eq(auth.rawId) and it.subjectType.eq(auth.principalName) and it.disabledAt.eq(null)
                }).toList()

                if (active.isEmpty()) throw NotFoundException()

                prove(
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
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> established(
        principal: PrincipalType<SUBJECT, ID>,
        subject: SUBJECT,
    ): Boolean {
        @Suppress("UNCHECKED_CAST")
        return modelInfo.table().count(condition {
            it.subjectId.eq(principal.idString(subject._id)) and
                    it.subjectType.eq(principal.name) and
                    active and
                    it.lastUsedAt.neq(null)
        }) > 0
    }
}