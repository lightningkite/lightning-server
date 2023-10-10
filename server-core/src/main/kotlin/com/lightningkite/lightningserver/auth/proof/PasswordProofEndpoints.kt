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
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration.Companion.hours

@OptIn(InternalSerializationApi::class)
class PasswordProofEndpoints(
    path: ServerPath,
    val database: () -> Database,
    val cache: () -> Cache,
    val proofHasher: () -> SecureHasher = secretBasis.hasher("proof"),
    val evaluatePassword: (String) -> Unit = { it }
) : ServerPathGroup(path), Authentication.DirectProofMethod {
    init {
        if (path.docName == null) path.docName = "PasswordProof"
    }

    override val name: String
        get() = "password"
    override val humanName: String
        get() = "Password"
    override val validates: String
        get() = "password"
    override val strength: Int
        get() = 5

    private val tables = HashMap<String, FieldCollection<PasswordSecret<Comparable<Any>>>>()

    @Suppress("UNCHECKED_CAST")
    fun table(subjectHandler: Authentication.SubjectHandler<*, *>) = tables.getOrPut(subjectHandler.name) {
        database().collection(
            PasswordSecret.serializer(subjectHandler.idSerializer),
            "PasswordSecretFor${subjectHandler.name}"
        ) as FieldCollection<PasswordSecret<Comparable<Any>>>
    }

    init {
        prepareModels()
        Tasks.onSettingsReady {
            Authentication.subjects.forEach {
                @Suppress("UNCHECKED_CAST")
                ModelRestEndpoints<HasId<*>, PasswordSecret<Comparable<Any>>, Comparable<Any>>(path("secrets/${it.value.name.lowercase()}"), modelInfo< HasId<*>, PasswordSecret<Comparable<Any>>, Comparable<Any>>(
                    serialization = ModelSerializationInfo(PasswordSecret.serializer(it.value.idSerializer as KSerializer<Comparable<Any>>), it.value.idSerializer as KSerializer<Comparable<Any>>),
                    authOptions = Authentication.isAdmin as AuthOptions<HasId<*>>,
                    getCollection = { table(it.value).withPermissions(ModelPermissions(
                        create = Condition.Always(),
                        read = Condition.Always(),
                        readMask = Mask(
                            listOf(
                                Condition.Never<PasswordSecret<Comparable<Any>>>() to Modification.OnField(
                                    PasswordSecret_hash(it.value.idSerializer as KSerializer<Comparable<Any>>),
                                    Modification.Assign("")
                                )
                            )
                        ),
                        update = Condition.Always(),
                        delete = Condition.Always(),
                    )) as FieldCollection<PasswordSecret<Comparable<Any>>> },
                    modelName = "PasswordSecret For ${it.value.name}"
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
        summary = "Establish a Password",
        inputType = EstablishPassword.serializer(),
        outputType = Unit.serializer(),
        description = "Generates a new One Time Password configuration.",
        authOptions = anyAuthRoot,
        errorCases = listOf(),
        examples = listOf(),
        implementation = { value: EstablishPassword ->
            evaluatePassword(value.password)
            if(value.hint?.contains(value.password, true) == true) throw BadRequestException("Hint cannot contain the password itself!")
            val secret = PasswordSecret(
                _id = auth.rawId as Comparable<Any>,
                hash = value.password.secureHash(),
                hint = value.hint,
            )
            table(auth.subject).deleteOneById(auth.rawId as Comparable<Any>)
            table(auth.subject).insertOne(secret)
            Unit
        }
    )

    val disable = path("existing").delete.api(
        summary = "Disable Password",
        inputType = Unit.serializer(),
        outputType = Boolean.serializer(),
        description = "Disables your password.",
        authOptions = anyAuthRoot,
        errorCases = listOf(),
        examples = listOf(),
        implementation = { _: Unit ->
            table(auth.subject).deleteOneById(auth.rawId as Comparable<Any>)
        }
    )

    val check = path("existing").get.api(
        summary = "Check Password",
        inputType = Unit.serializer(),
        outputType = SecretMetadata.serializer().nullable,
        description = "Returns information about your password, if you have one.",
        authOptions = anyAuthRoot,
        errorCases = listOf(),
        examples = listOf(),
        implementation = { _: Unit ->
            table(auth.subject).get(auth.rawId as Comparable<Any>)?.let {
                SecretMetadata(
                    establishedAt = it.establishedAt,
                    label = it.hint ?: "-"
                )
            }
        }
    )

    override val prove = path("prove").post.api(
        authOptions = noAuth,
        summary = "Prove $validates ownership",
        description = "Logs in to the given account with a password.  Limits to 10 attempts per hour.",
        errorCases = listOf(),
        examples = listOf(
            ApiExample(
                input = ProofEvidence(
                    "User|id",
                    "my password"
                ),
                output = Proof(
                    via = name,
                    of = validates,
                    strength = strength,
                    value = "User|id",
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
            if (!input.password.checkAgainstHash(secret.hash)) throw BadRequestException("User ID and code do not match")
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
        return table(handler).get(id as Comparable<Any>) != null
    }

    suspend fun <ID : Comparable<ID>> proofOption(handler: Authentication.SubjectHandler<*, ID>, id: ID): ProofOption? {
        return if (established(handler, id)) {
            ProofOption(info, key(handler, id))
        } else {
            null
        }
    }
}