package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.AnyId
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.sessions.proofs.Proof
import com.lightningkite.lightningserver.sessions.proofs.ProofOption
import com.lightningkite.lightningserver.sessions.proofs.extensions.verify
import com.lightningkite.lightningserver.sessions.proofs.proofMethods
import com.lightningkite.lightningserver.sessions.token.PrivateTinyTokenFormat
import com.lightningkite.lightningserver.sessions.token.TokenFormat
import com.lightningkite.lightningserver.toException
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.lightningserver.typed.invoke
import com.lightningkite.lightningserver.typed.sdk.SdkModule
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.lightningserver.typed.sdk.sdkSettings
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import kotlinx.serialization.builtins.ListSerializer
import kotlin.math.min
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

public abstract class AuthEndpoints<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
    principal: PrincipalType<SUBJECT, ID>,
    database: Runtime<Database>,
    private val proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proof"),
    tokenFormat: Runtime<TokenFormat> = Runtime { PrivateTinyTokenFormat() },
) : SessionManager<SUBJECT, ID>(principal, database, tokenFormat) {
    init {
        sdkSettings.defaultInfo = SdkModule.Info(principal.name + "Auth")
    }

    context(server: ServerRuntime)
    public abstract suspend fun requiredProofStrengthFor(subject: SUBJECT): Int

    private val errorNoSingleUser = LSError(
        404,
        detail = "no-single-user",
        message = "No single user '' was found."
    )
    private val errorInvalidProof = LSError(
        400,
        detail = "invalid-proof",
        message = "A given proof was invalid."
    )
    private val errorIrrelevantProof = LSError(
        400,
        detail = "irrelevant-proof",
        message = "A given proof was not related to the user."
    )
    private val errorExpiredProof = LSError(
        400,
        detail = "expired-proof",
        message = "A given proof expired."
    )
    private val errorNonexistentMethod = LSError(
        400,
        detail = "nonexistent-proof-method",
        message = "Could not find proof method for given proof."
    )

    private val errors = listOf(
        errorNoSingleUser,
        errorInvalidProof,
        errorIrrelevantProof,
        errorExpiredProof,
        errorNonexistentMethod
    )

    context(_: ServerRuntime)
    protected suspend fun newSession(
        request: LogInRequest,
        result: ProofsCheckResult<ID>
    ): Pair<Session<SUBJECT, ID>, RefreshToken>? {
        val subject = principal.fetch(result.id)

        return if (result.readyToLogIn) newSession(
            subjectId = result.id,
            label = request.label,
            expires = run {
                val a = result.expires
                val b = request.expires
                if (a != null && b != null) minOf(a, b) else a ?: b
            },
            scopes = request.scopes,
            stale = sessionStaleAfter(subject)?.let { now() + it }
        )
        else null
    }
    
    public val login: ApiHttpHandler<PathSpec0, HasId<AnyId>?, List<Proof>, IdAndAuthMethods<ID>> =
        path.path("login").post bind ApiHttpHandler(
            auth = noAuth,
            inputType = ListSerializer(Proof.serializer()),
            outputType = IdAndAuthMethods.serializer(principal.idSerializer),
            summary = "Log In",
            description = "Attempt to log in as a ${principal.name} using various proofs.",
            errorCases = errors,
//            belongsToInterface = belongsToInterface,
            implementation = { proofs: List<Proof> ->
                login2(LogInRequest(proofs))
            }
        )

    public val login2: ApiHttpHandler<PathSpec0, HasId<AnyId>?, LogInRequest, IdAndAuthMethods<ID>> =
        path.path("login2").post bind ApiHttpHandler(
            auth = noAuth,
            inputType = LogInRequest.serializer(),
            outputType = IdAndAuthMethods.serializer(principal.idSerializer),
            summary = "Log In With Limitations",
            description = "Attempt to log in as a ${principal.name} using various proofs.",
            errorCases = errors,
//            belongsToInterface = belongsToInterface,
            implementation = { input: LogInRequest ->
                proofsCheck(input.proofs).let {
                    IdAndAuthMethods(
                        id = it.id,
                        options = it.options,
                        strengthRequired = it.strengthRequired,
                        refreshToken = newSession(input, it)?.second?.string
                    )
                }
            }
        )

    public val proofsCheck: ApiHttpHandler<PathSpec0, HasId<AnyId>?, List<Proof>, ProofsCheckResult<ID>> =
        path.path("proofs-check").post bind ApiHttpHandler(
            auth = noAuth,
            inputType = ListSerializer(Proof.serializer()),
            outputType = ProofsCheckResult.serializer(principal.idSerializer),
            summary = "Check Proofs",
            description = "Check if you can log in as a ${principal.name} using various proofs.",
            errorCases = errors,
//            belongsToInterface = belongsToInterface,
            implementation = { proofs: List<Proof> ->
                proofs.forEach {
                    if (!proofSigner.await().verify(it)) throw errorInvalidProof.toException(data = it.via)
                    if (now() > it.at + 1.hours) throw errorExpiredProof.toException(data = it.via)
                }
                val used = proofs.map { it.via }.toSet()
                val users = proofs.mapNotNull { principal.fetchByProperty(it.property, it.value) }.distinctBy { it._id }

                val subject = users.singleOrNull() ?: run {
                    val properties = proofs.map { it.property }.toSet()
                    throw errorNoSingleUser.toException(
                        message = listOfNotNull(
                            if (users.isEmpty()) "No user was" else "Multiple users were",
                            "found with the",
                            if (properties.size > 1) "properties" else null,
                            proofs
                                .groupBy { it.property }
                                .toList()
                                .joinToString(", ") { pair ->
                                    "${pair.first} [${pair.second.map { it.value }.distinct().joinToString()}]"
                                }
                        ).joinToString(" "),
                    )
                }

                proofs.forEach {
                    if (principal.getProperty(subject, it.property) != it.value)
                        throw errorIrrelevantProof.toException(data = it.via)
                }

                val methods = serverRuntime.proofMethods
                    .filter { it.established(principal, subject) }
                    .associateBy { it.info.via }

                val strength = proofs
                    .groupBy { proof ->
                        val method = methods[proof.via] ?: throw errorNonexistentMethod.toException(data = proof.via)
                        method.info.property ?: method.info.via
                    }
                    .values
                    .sumOf { proofs -> proofs.maxOf { it.strength } }

                val maxStrengthPossible = methods.values
                    .groupBy { it.info.property ?: it.info.via }
                    .values
                    .sumOf { proofs -> proofs.maxOf { it.info.strength } }

                val requiredStrength = min(requiredProofStrengthFor(subject), maxStrengthPossible)

                ProofsCheckResult(
                    readyToLogIn = strength >= requiredStrength,
                    expires = sessionExpiration(subject),
                    id = subject._id,
                    options = proofMethods
                        .filter { it.info.via !in used }
                        .map {
                            ProofOption(
                                method = it.info,
                                value = it.info.property?.let { p ->
                                    principal.getProperty(subject, p)
                                }
                            )
                        },
                    strengthRequired = requiredStrength
                )
            }
        )

    public val sessions: ModelRestEndpoints<SUBJECT, Session<SUBJECT, ID>, Uuid> = path.path("sessions") include ModelRestEndpoints(info = sessionInfo)
}