package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.typed.parseUrlPartOrBadRequest
import com.lightningkite.lightningserver.typed.typed
import com.lightningkite.lightningserver.websocket.WebSockets
import kotlinx.serialization.builtins.serializer
import java.lang.Exception
import java.security.SecureRandom
import java.time.Duration
import java.util.*

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
    val authType = object: JwtTypedAuthorizationHandler.AuthType<USER> {
        override val name: String
            get() = userAccess.authInfo.type!!

        @Suppress("UNCHECKED_CAST")
        override fun tryCast(item: Any): USER? = userAccess.authInfo.tryCast(item)
        override suspend fun retrieve(reference: String): USER = userAccess.byId(Serialization.fromString(reference, userAccess.idSerializer))
        override fun serializeReference(item: USER): String = Serialization.toString(userAccess.id(item), userAccess.idSerializer)
    }

    val typedHandler = JwtTypedAuthorizationHandler.current(jwtSigner)
    init {
        typedHandler.types.add(authType)
        typedHandler.defaultType = authType
        path.docName = "Auth"
    }

    fun token(user: USER, expireDuration: Duration = jwtSigner().expiration): String = typedHandler.token(user, expireDuration)

    fun redirectToLanding(token: String): HttpResponse = HttpResponse.redirectToGet(landingRoute.path.toString() + "?jwt=${token}")
    fun redirectToLanding(user: USER): HttpResponse = HttpResponse.redirectToGet(landingRoute.path.toString() + "?jwt=${token(user, Duration.ofMinutes(5))}")

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