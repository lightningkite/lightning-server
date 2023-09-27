package com.lightningkite.lightningserver.auth.old

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.get
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.auth.proof.Proof
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.exceptions.ForbiddenException
import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.decodeUnwrappingString
import com.lightningkite.lightningserver.serialization.encodeUnwrappingString
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.typed.ApiExample
import com.lightningkite.lightningserver.typed.api
import com.lightningkite.lightningserver.typed.typed
import io.ktor.http.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Duration
import java.time.Instant

/**
 * Implements a basic set of authentication endpoints for you.
 * Must be used in concert with some kind of authentication mechanism, such as the ones in [EmailAuthEndpoints] or [SmsAuthEndpoints].
 * @param path The path to host the endpoints at.  Highly recommend using 'auth'.
 * @param userAccess Rules to access the user users, wherever they may be stored.  Typically obtained through [userEmailAccess] or similar.
 * @param landing The landing page for after a user is authenticated.  Defaults to the root.
 * @param handleToken The action to perform upon obtaining the token.  Defaults to redirecting to [landing], but respects paths given in the `destination` query parameter.
 */
open class BaseAuthEndpoints<USER : HasId<ID>, ID : Comparable<ID>>(
    path: ServerPath,
    val userAccess: UserAccess<USER, ID>,
    val hasher: () -> SecureHasher,
    val expiration: Duration,
    val emailExpiration: Duration,
    val landing: String = "/",
    val handleToken: suspend HttpRequest.(token: String) -> HttpResponse = { token ->
        val dest = queryParameter("destination") ?: landing
        if (dest.contains("://")) throw ForbiddenException("Destination ")
        HttpResponse.redirectToGet(
            to = queryParameter("destination") ?: landing,
            headers = {
                setCookie(HttpHeader.Authorization, token, maxAge = 31536000)
            }
        )
    }
) : ServerPathGroup(path) {

    val typeName = userAccess.authType.classifier?.toString()?.substringAfterLast('.') ?: "Unknown"
    val jwtPrefix = "$typeName|"
    val handler: Authentication.SubjectHandler<USER, ID> = object : Authentication.SubjectHandler<USER, ID> {
        override val name: String get() = typeName
        override val authType: AuthType get() = userAccess.authType
        override val idSerializer: KSerializer<ID> get() = userAccess.idSerializer
        override val subjectSerializer: KSerializer<USER> get() = userAccess.serializer
        override suspend fun fetch(id: ID): USER = userAccess.byId(id)

        override val idProofs: Set<Authentication.ProofMethod> get() = setOf()
        override val applicableProofs: Set<Authentication.ProofMethod> get() = setOf()
        override suspend fun authenticate(vararg proofs: Proof): Authentication.AuthenticateResult<USER, ID>? = null

    }

    init {
        Http.interceptors += { req, cont ->
            req.queryParameter("jwt")?.let { token ->
                val refreshed = refreshToken(token, expiration)
                HttpResponse.pathMoved(
                    req.endpoint.path.toString(
                        req.parts,
                        req.wildcard ?: ""
                    ) + req.queryParameters.filter { it.first != "jwt" }.joinToString("&") {
                        "${it.first}=${it.second.encodeURLParameter()}"
                    }
                ) {
                    setCookie(HttpHeader.Authorization, refreshed)
                }
            } ?: cont(req)
        }
        Authentication.register(handler)
        Authentication.readers += object : Authentication.Reader {
            override suspend fun request(request: Request): RequestAuth<*>? {
                try {
                    var token =
                        request.headers[HttpHeader.Authorization] ?: request.headers.cookies[HttpHeader.Authorization]
                        ?: return null
                    token = token.removePrefix("Bearer ")
                    val claims = hasher().verifyJwt(token) ?: return null
                    val sub = claims.sub ?: return null
                    if (!sub.startsWith(jwtPrefix)) return null
                    val id =
                        Serialization.json.decodeUnwrappingString(userAccess.idSerializer, sub.removePrefix(jwtPrefix))
                    return RequestAuth(
                        subject = handler,
                        rawId = id,
                        issuedAt = Instant.ofEpochSecond(claims.iat),
                        sessionId = null
                    )
                } catch (e: JwtException) {
                    throw UnauthorizedException(e.message ?: "JWT issue")
                }
            }
        }
    }

    init {
        path.docName = "Auth"
    }

    /**
     * Creates a JWT representing the given [user].
     */
    suspend fun refreshToken(token: String, expireDuration: Duration = expiration): String = hasher().signJwt(
        hasher().verifyJwt(token)!!.copy(
            exp = Instant.now().plus(expireDuration).epochSecond,
        )
    )

    /**
     * Creates a JWT representing the given [user].
     */
    suspend fun token(user: USER, expireDuration: Duration = expiration): String =
        tokenById(userAccess.id(user), expireDuration)

    /**
     * Creates a JWT representing the given user by [id].
     */
    suspend fun tokenById(id: ID, expireDuration: Duration = expiration): String = hasher().signJwt(
        JwtClaims(
            iss = generalSettings().publicUrl,
            aud = generalSettings().publicUrl,
            sub = jwtPrefix + Serialization.json.encodeUnwrappingString(userAccess.idSerializer, id),
            exp = Instant.now().plus(expireDuration).epochSecond,
            iat = Instant.now().epochSecond,
            nbf = Instant.now().epochSecond,
        )
    )

    /**
     * Gives an [HttpResponse] that logs in the user with the given [token].
     */
    fun redirectToLanding(token: String): HttpResponse =
        HttpResponse.redirectToGet(landingRoute.path.toString() + "?jwt=${token}")

    /**
     * Gives an [HttpResponse] that logs in as the given [user].
     */
    suspend fun redirectToLanding(user: USER): HttpResponse =
        HttpResponse.redirectToGet(landingRoute.path.toString() + "?jwt=${token(user, Duration.ofMinutes(5))}")

    /**
     * The landing endpoint that users arrive at after authenticating through any method.
     * Defers to [handleToken].
     */
    val landingRoute: HttpEndpoint = path("login-landing").get.handler {
        it.handleToken(tokenById(it.auth<USER>(userAccess.authType)!!.id))
    }

    val loggedIn = AuthOptions<USER>(setOf(AuthOption(userAccess.authType)))
    val refreshToken = path("refresh-token").get.api(
        authOptions = loggedIn,
        inputType = Unit.serializer(),
        outputType = String.serializer(),
        summary = "Refresh token",
        description = "Creates a new token for the user, which can be used to authenticate with the API via the header 'Authorization: Bearer [insert token here]'.",
        errorCases = listOf(),
        examples = listOf(ApiExample(input = Unit, output = "jwt.jwt.jwt")),
        implementation = { input: Unit ->
            token(user())
        }
    )
    val getSelf = path("self").get.api(
        authOptions = loggedIn,
        inputType = Unit.serializer(),
        outputType = userAccess.serializer,
        summary = "Get Self",
        description = "Retrieves the user that you currently authenticated as.",
        errorCases = listOf(),
        implementation = { _: Unit -> user() }
    )
    val anonymous = path("anonymous").get.api(
        authOptions = noAuth,
        inputType = Unit.serializer(),
        outputType = String.serializer(),
        summary = "Anonymous Token",
        description = "Creates a token for a new, anonymous user.  The token can be used to authenticate with the API via the header 'Authorization: Bearer [insert token here]",
        errorCases = listOf(),
        examples = listOf(ApiExample(input = Unit, output = "jwt.jwt.jwt")),
        implementation = { _: Unit ->
            token(userAccess.anonymous())
        }
    )
}
