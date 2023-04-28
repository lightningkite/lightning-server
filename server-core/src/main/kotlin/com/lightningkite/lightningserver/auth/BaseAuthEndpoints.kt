package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.typed.typed
import kotlinx.serialization.builtins.serializer
import java.time.Duration

/**
 * Implements a basic set of authentication endpoints for you.
 * Must be used in concert with some kind of authentication mechanism, such as the ones in [EmailAuthEndpoints] or [SmsAuthEndpoints].
 * @param path The path to host the endpoints at.  Highly recommend using 'auth'.
 * @param userAccess Rules to access the user users, wherever they may be stored.  Typically obtained through [userEmailAccess] or similar.
 * @param landing The landing page for after a user is authenticated.  Defaults to the root.
 * @param handleToken The action to perform upon obtaining the token.  Defaults to redirecting to [landing], but respects paths given in the `destination` query parameter.
 */
open class BaseAuthEndpoints<USER : Any, ID>(
    path: ServerPath,
    val userAccess: UserAccess<USER, ID>,
    val jwtSigner: () -> JwtSigner,
    val landing: String = "/",
    val handleToken: suspend HttpRequest.(token: String) -> HttpResponse = { token ->
        HttpResponse.redirectToGet(
            to = queryParameter("destination") ?: landing,
            headers = {
                setCookie(HttpHeader.Authorization, token, maxAge = 31536000)
            }
        )
    }
) : ServerPathGroup(path) {
    /**
     * The [JwtTypedAuthorizationHandler.AuthType] associated with this set of auth endpoints.
     */
    val authType = object : JwtTypedAuthorizationHandler.AuthType<USER> {
        override val name: String
            get() = userAccess.authInfo.type!!

        @Suppress("UNCHECKED_CAST")
        override fun tryCast(item: Any): USER? = userAccess.authInfo.tryCast(item)
        override suspend fun retrieve(reference: String): USER =
            userAccess.byId(Serialization.fromString(reference, userAccess.idSerializer))

        override fun serializeReference(item: USER): String =
            Serialization.toString(userAccess.id(item), userAccess.idSerializer)
    }

    /**
     * A reference to the [JwtTypedAuthorizationHandler] that has been set in [Authentication].
     */
    val typedHandler = JwtTypedAuthorizationHandler.current(jwtSigner)

    init {
        typedHandler.types.add(authType)
        if (typedHandler.defaultType != null) {
            typedHandler.defaultType = authType
        }
        path.docName = "Auth"
    }

    /**
     * Creates a JWT representing the given [user].
     */
    fun token(user: USER, expireDuration: Duration = jwtSigner().expiration): String =
        typedHandler.token(user, expireDuration)

    /**
     * Gives an [HttpResponse] that logs in the user with the given [token].
     */
    fun redirectToLanding(token: String): HttpResponse =
        HttpResponse.redirectToGet(landingRoute.path.toString() + "?jwt=${token}")

    /**
     * Gives an [HttpResponse] that logs in as the given [user].
     */
    fun redirectToLanding(user: USER): HttpResponse =
        HttpResponse.redirectToGet(landingRoute.path.toString() + "?jwt=${token(user, Duration.ofMinutes(5))}")

    /**
     * The landing endpoint that users arrive at after authenticating through any method.
     * Defers to [handleToken].
     */
    val landingRoute: HttpEndpoint = path("login-landing").get.handler {
        val subject = jwtSigner().verify(it.queryParameter("jwt")!!)
        it.handleToken(jwtSigner().token(subject))
    }

    val refreshToken = path("refresh-token").get.typed(
        authInfo = userAccess.authInfo,
        inputType = Unit.serializer(),
        outputType = String.serializer(),
        summary = "Refresh token",
        description = "Retrieves a new token for the user.",
        errorCases = listOf(),
        implementation = { user: USER, input: Unit ->
            token(user)
        }
    )
    val getSelf = path("self").get.typed(
        authInfo = userAccess.authInfo,
        inputType = Unit.serializer(),
        outputType = userAccess.serializer,
        summary = "Get Self",
        description = "Retrieves the user that you currently are",
        errorCases = listOf(),
        implementation = { user: USER, _: Unit -> user }
    )
    val anonymous = path("anonymous").get.typed(
        authInfo = userAccess.authInfo.copy(required = false),
        inputType = Unit.serializer(),
        outputType = String.serializer(),
        summary = "Anonymous Token",
        description = "Retrieves a token for a new, anonymous user.",
        errorCases = listOf(),
        implementation = { user: USER?, _: Unit ->
            return@typed token(userAccess.anonymous())
        }
    )
}