package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.Database
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.ModelPermissions
import com.lightningkite.lightningdb.and
import com.lightningkite.lightningdb.condition
import com.lightningkite.lightningdb.eq
import com.lightningkite.lightningdb.gte
import com.lightningkite.lightningdb.insertOne
import com.lightningkite.lightningdb.mask
import com.lightningkite.lightningdb.modification
import com.lightningkite.lightningdb.or
import com.lightningkite.lightningdb.updateOneById
import com.lightningkite.lightningdb.updateRestrictions
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.Authentication.ProofMethod
import com.lightningkite.lightningserver.auth.accepts
import com.lightningkite.lightningserver.auth.anyAuthRoot
import com.lightningkite.lightningserver.auth.idString
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.auth.plus
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.ModelRestEndpoints
import com.lightningkite.lightningserver.db.modelInfo
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.typed.Documentable
import com.lightningkite.lightningserver.typed.api
import com.lightningkite.lightningserver.typed.auth
import com.lightningkite.now
import com.lightningkite.serialization.notNull
import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.exceptions.ForbiddenException
import com.lightningkite.lightningserver.settings.generalSettings
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class PasskeyProofEndpoints<USER : HasId<*>>(
    path: ServerPath,
    val database: () -> Database,
    val challenge: PasskeyChallengeHandler,
    registrationAuthOptions: AuthOptions<USER>,
    val nameTemplate: (USER) -> String,
    val proofHasher: () -> SecureHasher = secretBasis.hasher("proof"),
    val displayNameTemplate: (USER) -> String = nameTemplate,
) : ServerPathGroup(path), ProofMethod {

    init {
        path.docName = "PasskeyProof"
    }

    override val info = ProofMethodInfo(
        via = "passkey",
        property = null,
        strength = 5
    )

    init {
        Authentication.register(this)
    }

    val loggedInInterfaceInfo = Documentable.InterfaceInfo(path, "AuthenticatedPasskeyProofClientEndpoints", listOf())
    val interfaceInfo = Documentable.InterfaceInfo(path, "PasskeyProofClientEndpoints", listOf())

    private val active get() = condition<PasskeyCredential> { it.disabledAt.eq(null) and (it.expiresAt.eq(null) or it.expiresAt.notNull.gte(now())) }

    val modelInfo = database.modelInfo<HasId<*>?, PasskeyCredential, String>(
        authOptions = anyAuthRoot + Authentication.isAdmin,
        permissions = {
            val admin = condition<PasskeyCredential>(Authentication.isAdmin.accepts(authOrNull))
            val mine = authOrNull?.let { a ->
                condition<PasskeyCredential> {
                    it.subjectId.eq(a.idString) and it.subjectName.eq(a.subject.name)
                }
            } ?: Condition.Never
            ModelPermissions(
                create = Condition.Never,
                read = admin or mine,
                readMask = mask {
                    it.publicKeyDerBase64.mask("")
                },
                update = admin or (mine and active),
                updateRestrictions = updateRestrictions {
                    it.subjectName.cannotBeModified()
                    it.subjectId.cannotBeModified()
                    it.publicKeyDerBase64.cannotBeModified()
                    it.algorithm.cannotBeModified()
                },
                delete = Condition.Never,
            )
        }
    )

    val rest = ModelRestEndpoints(path("credentials"), modelInfo)

    val registerStart = path("registerStart").post.api(
        belongsToInterface = loggedInInterfaceInfo,
        authOptions = registrationAuthOptions,
        summary = "Begins a passkey credential registration",
        description = "Returns a challenge to be passed on to a client authenticator for the creation of a new passkey.",
        errorCases = listOf(),
        examples = listOf(),
        successCode = HttpStatus.OK,
        implementation = { _: Unit ->
            val challenge = challenge.establishForRegistration(auth.subject.name, auth.idString)
            val rp = PublicKeyCredentialRpEntity(name = generalSettings.name)
            val pkUser = PublicKeyCredentialUserEntity(
                displayName = displayNameTemplate(user()),
                id = auth.idString,
                name = nameTemplate(user())
            )

            PublicKeyCredentialCreationOptions(
                challenge = challenge,
                rp = rp,
                user = pkUser
            )
        }
    )

    val registerFinish = path("registerFinish").post.api<HasId<*>, AttestedPublicKeyCredential, Unit>(
        belongsToInterface = loggedInInterfaceInfo,
        authOptions = registrationAuthOptions,
        summary = "Finalizes a passkey credential registration",
        description = "Accepts a public key credential saved in a client authenticator and a previously issued challenge to verify and persist a recently created passkey.",
        errorCases = listOf(),
        examples = listOf(),
        successCode = HttpStatus.OK,
        implementation = { created: AttestedPublicKeyCredential ->
            // TODO: We should verify the challenge signature, although since this endpoint has an auth gate anyways, it probably doesn't matter...
            val credential = PasskeyCredential(
                _id = created.id,
                subjectName = auth.subject.name,
                subjectId = auth.idString,
                publicKeyDerBase64 = created.response.publicKey,
                algorithm = created.response.publicKeyAlgorithm,
            )
            modelInfo.collection().insertOne(credential)
        }
    )

    // At this point, the subject need not identify themselves. This is because the passkey that the client
    // authenticator uses to sign the challenge will be used in the "prove" step to determine the subject
    // that is signing in.
    val start = path("start").post.api(
        belongsToInterface = interfaceInfo,
        authOptions = noAuth,
        summary = "Begins a passkey challenge process",
        description = "Returns a challenge to be passed on to a client authenticator for signing.",
        errorCases = listOf(),
        examples = listOf(),
        successCode = HttpStatus.OK,
        implementation = { _: Unit ->
            PublicKeyCredentialRequestOptions(
                challenge = challenge.establishForLogin()
            )
        }
    )

    @OptIn(ExperimentalEncodingApi::class)
    val prove = path("prove").post.api<HasId<*>?, AssertedPublicKeyCredential, Proof>(
        belongsToInterface = interfaceInfo,
        authOptions = noAuth,
        summary = "Begins a passkey challenge process",
        description = "Returns a challenge to be passed on to a client authenticator for signing.",
        errorCases = listOf(),
        examples = listOf(),
        successCode = HttpStatus.OK,
        implementation = { response: AssertedPublicKeyCredential ->
            val passkey = modelInfo.collection().find(condition {
                it._id.eq(response.id) and active
            }).firstOrNull() ?: throw ForbiddenException("Invalid or inactive passkey")

            // See Step 11 of 6.3.3. The `authenticatorGetAssertion` Operation
            // https://w3c.github.io/webauthn/#sctn-op-get-assertion
            val authenticatorDataRaw = Base64.UrlSafe.decode(response.response.authenticatorData)
            val clientDataDecoded = Base64.UrlSafe.decode(response.response.clientDataJSON)
            val clientData: ClientData = Json.decodeFromString(clientDataDecoded.decodeToString())

            challenge.assertForLogin(clientData.challenge)

            val clientDataHash = MessageDigest.getInstance("SHA-256").digest(clientDataDecoded)
            val signatureContents = authenticatorDataRaw + clientDataHash

            when (passkey.algorithm) {
                PublicKeyAlgorithm.ES256 -> SignatureVerifier.ES256()
                PublicKeyAlgorithm.EdDSA -> SignatureVerifier.EdDSA()
                else -> TODO()
            }.verify(
                signature = Base64.UrlSafe.decode(response.response.signature),
                expected = signatureContents,
                publicKey = Base64.UrlSafe.decode(passkey.publicKeyDerBase64)
            )

            modelInfo.collection().updateOneById(passkey._id, modification {
                it.lastUsedAt assign now()
            })

            proofHasher().makeProof(
                info = info,
                property = "_id",
                value = passkey.subjectId,
                at = now()
            )
        }
    )

    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> established(
        handler: Authentication.SubjectHandler<SUBJECT, ID>,
        item: SUBJECT
    ): Boolean {
        @Suppress("UNCHECKED_CAST")
        return modelInfo.collection().count(condition {
            it.subjectId.eq(handler.idString(item._id)) and
                    it.subjectName.eq(handler.name) and
                    active
        }) > 0
    }
}