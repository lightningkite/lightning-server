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
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.decodeUnwrappingString
import com.lightningkite.lightningserver.serialization.encodeUnwrappingString
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.typed.*
import dev.turingcomplete.kotlinonetimepassword.HmacAlgorithm
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordConfig
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

@OptIn(InternalSerializationApi::class)
class OneTimePasswordProofEndpoints(
    path: ServerPath,
    val proofHasher: () -> SecureHasher,
    val database: () -> Database,
    val cache: () -> Cache,
    val config: TimeBasedOneTimePasswordConfig = TimeBasedOneTimePasswordConfig(
        timeStep = 30,
        timeStepUnit = TimeUnit.SECONDS,
        codeDigits = 6,
        hmacAlgorithm = HmacAlgorithm.SHA1
    )
) : ServerPathGroup(path), Authentication.DirectProofMethod {
    override val name: String
        get() = "otp"
    override val humanName: String
        get() = "One-time Password"
    override val validates: String
        get() = "optKey"
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
                ModelRestEndpoints<HasId<*>, OtpSecret<Comparable<Any>>, Comparable<Any>>(path("secrets/${it.value.name.lowercase()}"), modelInfo< HasId<*>, OtpSecret<Comparable<Any>>, Comparable<Any>>(
                    serialization = ModelSerializationInfo(OtpSecret.serializer(it.value.idSerializer.also { println(it.descriptor.serialName) } as KSerializer<Comparable<Any>>).also {
                        println(it.descriptor.serialName)
                        println(it.serializableProperties?.joinToString { "${it.name}: ${it.serializer.descriptor.serialName}" })
                        println(it.descriptor.getElementDescriptor(0).serialName)
                    }, it.value.idSerializer as KSerializer<Comparable<Any>>),
                    authOptions = Authentication.isSuperUser as AuthOptions<HasId<*>>,
                    getCollection = { table(it.value) as FieldCollection<OtpSecret<Comparable<Any>>> },
                    modelName = "OtpSecret For ${it.value.name}"
                ))
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
        inputType = String.serializer(),
        outputType = String.serializer(),
        description = "Generates a new One Time Password configuration.",
        authOptions = anyAuth,
        errorCases = listOf(),
        examples = listOf(),
        implementation = { label: String ->
            val secret = OtpSecret(
                _id = auth.rawId as Comparable<Any>,
                secret = ByteArray(32).also { SecureRandom.getInstanceStrong().nextBytes(it) },
                label = label,
                issuer = generalSettings().projectName,
                config = config
            )
            table(auth.subject).deleteOneById(auth.rawId as Comparable<Any>)
            table(auth.subject).insertOne(secret)
            secret.url
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
                    at = Instant.now(),
                    signature = "opaquesignaturevalue"
                )
            )
        ),
        successCode = HttpStatus.OK,
        implementation = { input: ProofEvidence ->
            val postedAt = Instant.now()
            val cacheKey = "otp-count-${input.value}"
            cache().add(cacheKey, 1, Duration.ofHours(1))
            val ct = (cache().get<Int>(cacheKey) ?: 0)
            if (ct > 5) throw BadRequestException("Too many attempts; please wait.")
            val (subject, id) = key(input.value)
            @Suppress("UNCHECKED_CAST")
            val secret = table(subject).get(id as Comparable<Any>)
                ?: throw BadRequestException("User ID and code do not match")
            println("Expect ${secret.generator.generate()}")
            if (!secret.generator.isValid(input.secret, postedAt)) throw BadRequestException("User ID and code do not match")
            cache().remove(cacheKey)
            proofHasher().makeProof(
                info = info,
                value = input.value,
                at = Instant.now()
            )
        }
    )
}