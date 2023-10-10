package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningdb.*
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
import com.lightningkite.lightningserver.serialization.decodeUnwrappingString
import com.lightningkite.lightningserver.serialization.encodeUnwrappingString
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.typed.*
import dev.turingcomplete.kotlinonetimepassword.HmacAlgorithm
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordConfig
import com.lightningkite.now
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import java.security.SecureRandom
import kotlinx.datetime.toJavaInstant
import kotlinx.serialization.builtins.nullable
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours

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
        if (path.docName == null) path.docName = "OneTimePasswordProof"
    }

    override val name: String
        get() = "otp"
    override val humanName: String
        get() = "One-time Password"
    override val validates: String
        get() = "otp"
    override val strength: Int
        get() = 5

    private val tables = HashMap<String, FieldCollection<OtpSecret<Comparable<Any>>>>()

    @Suppress("UNCHECKED_CAST")
    fun table(subjectHandler: Authentication.SubjectHandler<*, *>) = tables.getOrPut(subjectHandler.name) {
        database().collection(
            OtpSecret.serializer(subjectHandler.idSerializer),
            "OtpSecretFor${subjectHandler.name}"
        ) as FieldCollection<OtpSecret<Comparable<Any>>>
    }

    init {
        prepareModels()
        Tasks.onSettingsReady {
            Authentication.subjects.forEach {
                @Suppress("UNCHECKED_CAST")
                ModelRestEndpoints<HasId<*>, OtpSecret<Comparable<Any>>, Comparable<Any>>(
                    path("secrets/${it.value.name.lowercase()}"),
                    modelInfo<HasId<*>, OtpSecret<Comparable<Any>>, Comparable<Any>>(
                        serialization = ModelSerializationInfo(
                            OtpSecret.serializer(it.value.idSerializer as KSerializer<Comparable<Any>>),
                            it.value.idSerializer as KSerializer<Comparable<Any>>
                        ),
                        authOptions = Authentication.isAdmin as AuthOptions<HasId<*>>,
                        getCollection = {
                            table(it.value).withPermissions(
                                ModelPermissions(
                                    create = Condition.Always(),
                                    read = Condition.Always(),
                                    readMask = Mask(
                                        listOf(
                                            Condition.Never<OtpSecret<Comparable<Any>>>() to Modification.OnField(
                                                OtpSecret_secretBase32(it.value.idSerializer as KSerializer<Comparable<Any>>),
                                                Modification.Assign("")
                                            )
                                        )
                                    ),
                                    update = Condition.Always(),
                                    delete = Condition.Always(),
                                )
                            ) as FieldCollection<OtpSecret<Comparable<Any>>>
                        },
                        modelName = "OtpSecret For ${it.value.name}"
                    )
                )
            }
        }
    }

    fun <ID : Comparable<ID>> key(subjectHandler: Authentication.SubjectHandler<*, ID>, id: ID): String =
        subjectHandler.name + "|" + Serialization.json.encodeUnwrappingString(subjectHandler.idSerializer, id)

    fun key(id: String): Pair<Authentication.SubjectHandler<*, *>, Any?> {
        val subject = id.substringBefore('|', "")
        val handler = Authentication.subjects.values.find { it.name == subject }
            ?: throw IllegalArgumentException("No subject $subject recognized")
        return handler to Serialization.json.decodeUnwrappingString(handler.idSerializer, id.substringAfter('|'))
    }

    val establish = path("establish").post.api(
        summary = "Establish an One Time Password",
        inputType = EstablishOtp.serializer(),
        outputType = String.serializer(),
        description = "Generates a new One Time Password configuration.",
        authOptions = anyAuthRoot,
        errorCases = listOf(),
        examples = listOf(),
        implementation = { input: EstablishOtp ->
            val secret = OtpSecret(
                _id = auth.rawId as Comparable<Any>,
                secret = ByteArray(32).also { SecureRandom.getInstanceStrong().nextBytes(it) },
                label = input.label ?: "",
                issuer = generalSettings().projectName,
                config = config,
                active = false,
            )
            table(auth.subject).insertOne(secret)
            secret.url
        }
    )

    val confirm = path("existing").post.api(
        summary = "Confirm One Time Password",
        inputType = String.serializer(),
        outputType = Unit.serializer(),
        description = "Confirms your OTP, making it fully active",
        authOptions = anyAuthRoot,
        errorCases = listOf(),
        examples = listOf(),
        implementation = { code: String ->
            val existing = table(auth.subject).get(auth.rawId as Comparable<Any>) ?: throw NotFoundException()
            @Suppress("UNCHECKED_CAST")
            prove.implementation(
                AuthAndPathParts(null, null, arrayOf()),
                ProofEvidence(
                    key(
                        auth.subject as Authentication.SubjectHandler<*, Comparable<Any?>>,
                        auth.rawId as Comparable<Any?>
                    ), code
                )
            )
            Unit
        }
    )

    val disable = path("existing").delete.api(
        summary = "Disable One Time Password",
        inputType = Unit.serializer(),
        outputType = Boolean.serializer(),
        description = "Disables your one-time password.",
        authOptions = anyAuthRoot,
        errorCases = listOf(),
        examples = listOf(),
        implementation = { _: Unit ->
            table(auth.subject).deleteOneById(auth.rawId as Comparable<Any>)
        }
    )

    val check = path("existing").get.api(
        summary = "Check One Time Password",
        inputType = Unit.serializer(),
        outputType = SecretMetadata.serializer().nullable,
        description = "Returns information about your OTP, if one exists.",
        authOptions = anyAuthRoot,
        errorCases = listOf(),
        examples = listOf(),
        implementation = { _: Unit ->
            table(auth.subject).get(auth.rawId as Comparable<Any>)?.let {
                SecretMetadata(
                    establishedAt = it.establishedAt,
                    label = it.label
                )
            }
        }
    )

    override val prove = path("prove").post.api(
        authOptions = noAuth,
        summary = "Prove $validates ownership",
        description = "Logs in to the given account with an OTP code.  Limits to 10 attempts per hour.",
        errorCases = listOf(),
        examples = listOf(
            ApiExample(
                input = ProofEvidence(
                    "some-id",
                    "000000"
                ),
                output = Proof(
                    via = name,
                    of = validates,
                    strength = strength,
                    value = "some-id",
                    at = now(),
                    signature = "opaquesignaturevalue"
                )
            )
        ),
        successCode = HttpStatus.OK,
        implementation = { input: ProofEvidence ->
            val postedAt = now()
            val cacheKey = "otp-count-${input.key}"
            cache().add(cacheKey, 1, 1.hours)
            val ct = (cache().get<Int>(cacheKey) ?: 0)
            if (ct > 5) throw BadRequestException("Too many attempts; please wait.")
            val (subject, id) = key(input.key)
            @Suppress("UNCHECKED_CAST")
            val secret = table(subject).get(id as Comparable<Any>)
                ?: throw BadRequestException("User ID and code do not match")
            if (!secret.generator.isValid(
                    input.password,
                    postedAt.toJavaInstant()
                )
            ) throw BadRequestException("User ID and code do not match")
            if (!secret.active) {
                val table = table(subject)
                table.updateOneById(id, modification(DataClassPathSelf(table.serializer)) {
                    it.active assign true
                })
            }
            cache().remove(cacheKey)
            proofHasher().makeProof(
                info = info,
                value = input.key,
                at = now()
            )
        }
    )

    suspend fun <ID : Comparable<ID>> established(handler: Authentication.SubjectHandler<*, ID>, id: ID): Boolean {
        @Suppress("UNCHECKED_CAST")
        return table(handler).get(id as Comparable<Any>)?.active == true
    }

    suspend fun <ID : Comparable<ID>> proofOption(handler: Authentication.SubjectHandler<*, ID>, id: ID): ProofOption? {
        return if (established(handler, id)) {
            ProofOption(info, key(handler, id))
        } else {
            null
        }
    }
}