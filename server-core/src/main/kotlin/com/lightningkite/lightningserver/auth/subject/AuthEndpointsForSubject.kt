package com.lightningkite.lightningserver.auth.subject

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.oauth.OauthResponse
import com.lightningkite.lightningserver.auth.oauth.OauthTokenRequest
import com.lightningkite.lightningserver.auth.proof.*
import com.lightningkite.lightningserver.auth.subject.*
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.ModelInfo
import com.lightningkite.lightningserver.db.ModelSerializationInfo
import com.lightningkite.lightningserver.encryption.SecureHasher
import com.lightningkite.lightningserver.exceptions.HttpStatusException
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.typed.typed
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.reflect.KType

class AuthEndpointsForSubject<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
    path: ServerPath,
    val sessionType: KType,
    val handler: Authentication.SubjectHandler<SUBJECT, ID>,
    val database: () -> Database,
    val hasher: () -> SecureHasher,
) : ServerPathGroup(path) {
    val info = ModelInfo<SUBJECT, Session<SUBJECT, ID>, String>(
        modelName = "${handler.subjectSerializer.descriptor.serialName} Session",
        serialization = ModelSerializationInfo(
            AuthRequirement(handler.authType, true),
            Session.serializer(handler.subjectSerializer, handler.idSerializer),
            idSerializer = String.serializer()
        ),
        getCollection = { database().collection(sessionType, "SessionFor${handler.subjectSerializer.descriptor.serialName}") },
        forUser = { subject ->
            withPermissions(
                permissions = ModelPermissions(
                    create = Condition.Never(),
                    read = condition { it.subjectId eq subject._id },
                    update = Condition.Never(),
                    delete = condition { it.subjectId eq subject._id },
                )
            )
        }
    )
    val errorNoSingleUser = LSError(
        404,
        detail = "no-single-user",
        message = "No single user could be found that matches the given credentials."
    )
    val errorInvalidProof = LSError(
        400,
        detail = "invalid-proof",
        message = "A given proof was invalid."
    )
    @Suppress("UNREACHABLE_CODE")
    val login = path.post.typed(
        authRequirement = AuthRequirement.none,
        inputType = ListSerializer(Proof.serializer()),
        outputType = IdAndAuthMethods.serializer(handler.idSerializer),
        summary = "Log In",
        description = "Attempt to log in as a ${handler.name} using various proofs.  Valid proofs types are ${
            handler.idProofs.plus(
                handler.applicableProofs
            ).joinToString()
        }",
        errorCases = listOf(errorNoSingleUser, errorInvalidProof),
        implementation = { none: Unit, proofs: List<Proof> ->
            proofs.forEach {
                if (!hasher().verify(it)) throw HttpStatusException(errorInvalidProof)
            }
            val result =
                handler.authenticate(*proofs.toTypedArray()) ?: throw HttpStatusException(errorNoSingleUser)
            val strength = proofs.sumOf { it.strength }
            IdAndAuthMethods(
                session = if (strength >= result.strengthRequired) Session<SUBJECT, ID>(
                    subjectId = result.id!!,
                    scopes = null,
                    ips = setOf(),
                    userAgents = setOf(),
                ).also { info.collection().insertOne(it) }._id else null,
                id = result.id,
                options = result.options,
                strengthRequired = result.strengthRequired
            )
        }
    )

    val createSubSession = path.post.typed(
        authRequirement = AuthRequirement(handler.authType, true),
        inputType = SubSessionRequest.serializer(),
        outputType = String.serializer(),
        summary = "Create Sub Session",
        description = "Creates a session with more limited authorization",
        errorCases = listOf(),
        implementation = { user: SUBJECT, request: SubSessionRequest ->
            val session = Session<SUBJECT, ID>(
                label = request.label,
                subjectId = user._id,
                scopes = request.scopes,
                expires = request.expires,
                oauthClient = request.oauthClient,
                ips = setOf(),
                userAgents = setOf(),
            )
            info.collection().insertOne(session)
            session._id
        }
    )

    val token = path.get.typed(
        summary = "Get Token",
        errorCases = listOf(),
        implementation = { _: Unit, input: OauthTokenRequest ->
            input.refresh_token
            OauthResponse(
                access_token = TODO()
            )
        }
    )
}