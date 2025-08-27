package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.AnyId
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.RequestPredicates
import com.lightningkite.lightningserver.auth.acceptsAllScopes
import com.lightningkite.lightningserver.auth.acceptsAnyScopes
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.bind
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.sessions.proofs.Proof
import com.lightningkite.lightningserver.sessions.proofs.ProofOption
import com.lightningkite.lightningserver.sessions.proofs.extensions.verify
import com.lightningkite.lightningserver.sessions.proofs.proofMethods
import com.lightningkite.lightningserver.sessions.token.PrivateTinyTokenFormat
import com.lightningkite.lightningserver.sessions.token.TokenFormat
import com.lightningkite.lightningserver.toException
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.invoke
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import kotlinx.serialization.builtins.ListSerializer
import kotlin.math.min
import kotlin.time.Duration.Companion.hours

public abstract class AuthEndpoints<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
    principal: PrincipalType<SUBJECT, ID>,
    database: Runtime<Database>,
    private val proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proofs"),
    tokenFormat: Runtime<TokenFormat> = Runtime { PrivateTinyTokenFormat() },
) : SessionManager<SUBJECT, ID>(principal, database, tokenFormat) {

    context(server: ServerRuntime)
    public open suspend fun requiredProofStrengthFor(subject: SUBJECT): Int = 5

    context(server: ServerRuntime)
    public open suspend fun authLimitsFor(subject: SUBJECT): RequestPredicates? = null

    context(server: ServerRuntime)
    public open suspend fun authForbidFor(subject: SUBJECT): RequestPredicates? = null


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
        message = "A given proof has a via with no corresponding proof-method"
    )

    private val errors = listOf(
        errorNoSingleUser,
        errorInvalidProof,
        errorIrrelevantProof,
        errorExpiredProof,
        errorNonexistentMethod
    )

    context(_: ServerRuntime)
    private suspend fun newSession(
        request: LogInRequest,
        result: ProofsCheckResult<ID>
    ): Pair<Session<SUBJECT, ID>, RefreshToken>? {
        val subject = principal.fetch(result.id)

        val limits = authLimitsFor(subject)
        val forbidden = authForbidFor(subject)

        if (limits != null && forbidden != null) {
            val intersection = limits.intersect(forbidden)
            if (intersection.isNotEmpty()) throw IllegalStateException(
                """
                    Intersections between authLimitsFor and authForbidFor are not allowed, as it is a contradiction. 
                    
                    Subject: $subject
                    
                    Intersection: $intersection
                """.trimIndent()
            )
        }

        if (limits?.scopes?.acceptsAllScopes(request.scopes) == false) throw ForbiddenException("You are limited to scopes ${limits.scopes}")
        if (forbidden?.scopes?.acceptsAnyScopes(request.scopes) == true) throw ForbiddenException("You are forbidden from scopes ${forbidden.scopes}")

        return if (result.readyToLogIn) newSession(
            subjectId = result.id,
            label = request.label,
            expires = run {
                val a = result.expires
                val b = request.expires
                if (a != null && b != null) minOf(a, b) else a ?: b
            },
            limitTo = limits,
            forbid = forbidden,
            stale = sessionStaleAfter(subject)?.let { now() + it }
        )
        else null
    }
    
    public val login: Locationed<HttpEndpoint<PathSpec0>, ApiHttpHandler<PathSpec0, HasId<AnyId>?, List<Proof>, IdAndAuthMethods<ID>>> =
        path.path("login").post bind ApiHttpHandler(
            auth = noAuth,
            inputType = ListSerializer(Proof.serializer()),
            outputType = IdAndAuthMethods.serializer(principal.idSerializer),
            summary = "Log In",
            description = "Attempt to log in as a ${principal.name} using various proofs.",
            errorCases = errors,
            handler = { proofs: List<Proof> ->
                login2(LogInRequest(proofs, scopes = emptySet())) // Uses empty set so that enforced limitations don't conflict. May still be granted root access
            }
        )

    public val login2: Locationed<HttpEndpoint<PathSpec0>, ApiHttpHandler<PathSpec0, HasId<AnyId>?, LogInRequest, IdAndAuthMethods<ID>>> =
        path.path("login2").post bind ApiHttpHandler(
            auth = noAuth,
            inputType = LogInRequest.serializer(),
            outputType = IdAndAuthMethods.serializer(principal.idSerializer),
            summary = "Log In With Limitations",
            description = "Attempt to log in as a ${principal.name} using various proofs.",
            errorCases = errors,
            handler = { input: LogInRequest ->
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

    public val proofsCheck: Locationed<HttpEndpoint<PathSpec0>, ApiHttpHandler<PathSpec0, HasId<AnyId>?, List<Proof>, ProofsCheckResult<ID>>> =
        path.path("proofs-check").post bind ApiHttpHandler(
            auth = noAuth,
            inputType = ListSerializer(Proof.serializer()),
            outputType = ProofsCheckResult.serializer(principal.idSerializer),
            summary = "Check Proofs",
            description = "Check if you can log in as a ${principal.name} using various proofs.",
            errorCases = errors,
            handler = { proofs: List<Proof> ->
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

                val methods = proofMethods
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
}