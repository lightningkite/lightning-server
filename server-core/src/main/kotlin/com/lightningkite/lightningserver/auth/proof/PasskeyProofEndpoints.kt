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
import com.lightningkite.lightningdb.or
import com.lightningkite.lightningdb.updateRestrictions
import com.lightningkite.lightningserver.auth.Authentication
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

class PasskeyProofEndpoints(
    path: ServerPath,
    val database: () -> Database,
    val challenge: PasskeyChallengeHandler,
    val interfaceInfo: Documentable.InterfaceInfo,
) : ServerPathGroup(path) {

    init {
        path.docName = "PasskeyProof"
    }

/*    override val info = ProofMethodInfo(
        via = "passkey",
        property = null,
        strength = 5
    )

    init {
        Authentication.register(this)
    }*/

    private val active get() = condition<PasskeyCredential> { it.disabledAt.eq(null) and (it.expiresAt.eq(null) or it.expiresAt.notNull.gte(now())) }

    val modelInfo = database.modelInfo<HasId<*>?, PasskeyCredential, String>(
        authOptions = anyAuthRoot + Authentication.isAdmin,
        permissions = {
            val admin = condition<PasskeyCredential>(Authentication.isAdmin.accepts(authOrNull))
            val mine = authOrNull?.let { a ->
                condition<PasskeyCredential> {
                    it.subjectId.eq(a.idString) and it.subjectType.eq(a.subject.name)
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
                    it.subjectType.cannotBeModified()
                    it.subjectId.cannotBeModified()
                    it.publicKeyDerBase64.cannotBeModified()
                    it.algorithm.cannotBeModified()
                },
                delete = Condition.Never,
            )
        }
    )

    val rest = ModelRestEndpoints(path("credentials"), modelInfo)

    val registerStart = path("registerStart").post.api<HasId<*>, Unit, String>(
        belongsToInterface = interfaceInfo,
        authOptions = anyAuthRoot,
        summary = "Begins a passkey credential registration",
        description = "Returns a challenge to be passed on to a client authenticator for the creation of a new passkey.",
        errorCases = listOf(),
        examples = listOf(),
        successCode = HttpStatus.OK,
        implementation = {
            challenge.establishForRegistration(auth.subject.name, auth.idString)
        }
    )

    val registerFinish = path("registerFinish").post.api<HasId<*>, PublicKeyCredential, Unit>(
        belongsToInterface = interfaceInfo,
        authOptions = anyAuthRoot,
        summary = "Finalizes a passkey credential registration",
        description = "Accepts a public key credential saved in a client authenticator and a previously issued challenge to verify and persist a recently created passkey.",
        errorCases = listOf(),
        examples = listOf(),
        successCode = HttpStatus.OK,
        implementation = { created: PublicKeyCredential ->
            // TODO: We should verify the challenge signature, although since this endpoint has an auth gate anyways, it probably doesn't matter...
            val credential = PasskeyCredential(
                _id = created.id,
                subjectType = auth.subject.name,
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
            challenge.establishForLogin()
        }
    )

    val prove = path("prove").post.api<HasId<*>?, FinishProof, Proof>(
        belongsToInterface = interfaceInfo,
        authOptions = noAuth,
        summary = "Begins a passkey challenge process",
        description = "Returns a challenge to be passed on to a client authenticator for signing.",
        errorCases = listOf(),
        examples = listOf(),
        successCode = HttpStatus.OK,
        implementation = {
            TODO()
        }
    )

}