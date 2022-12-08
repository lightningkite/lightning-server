package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.typed.typed
import com.lightningkite.lightningserver.websocket.WebSockets
import kotlinx.serialization.builtins.serializer
import java.lang.Exception

open class BaseAuthEndpoints<USER : Any, ID>(
    path: ServerPath,
    val userAccess: UserAccess<USER, ID>,
    val jwtSigner: () -> JwtSigner,
    val landing: String = "/",
    val handleToken: suspend HttpRequest.(token: String) -> HttpResponse = { token ->
        HttpResponse.redirectToGet(
            to = queryParameter("destination") ?: landing,
            headers = {
                setCookie(HttpHeader.Authorization, token)
            }
        )
    }
) : ServerPathGroup(path) {
    init {
        Authorization.handler = object : Authorization.Handler<USER> {
            override suspend fun http(request: HttpRequest): USER? {
                return request.jwt<ID>(jwtSigner(), userAccess.idSerializer)?.let { userAccess.byId(it) }
            }

            override suspend fun ws(request: WebSockets.ConnectEvent): USER? {
                return request.jwt<ID>(jwtSigner(), userAccess.idSerializer)?.let { userAccess.byId(it) }
            }

            override fun userToIdString(user: USER): String =
                Serialization.json.encodeToString(userAccess.idSerializer, userAccess.id(user))

            override suspend fun idStringToUser(id: String): USER =
                userAccess.byId(Serialization.json.decodeFromString(userAccess.idSerializer, id))
        }
        path.docName = "Auth"
    }

    val landingRoute: HttpEndpoint = path("login-landing").get.handler {
        val subject = jwtSigner().verify(userAccess.idSerializer, it.queryParameter("jwt")!!)
        it.handleToken(jwtSigner().token(userAccess.idSerializer, subject))
    }
    val refreshToken = path("refresh-token").get.typed(
        authInfo = userAccess.authInfo,
        inputType = Unit.serializer(),
        outputType = String.serializer(),
        summary = "Refresh token",
        description = "Retrieves a new token for the user.",
        errorCases = listOf(),
        implementation = { user: USER, input: Unit ->
            jwtSigner().token(userAccess.idSerializer, user.let(userAccess::id))
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
        authInfo = AuthInfo(
            checker = { try { userAccess.authInfo.checker(it) } catch(e: Exception) { null } },
            type = userAccess.authInfo.type,
            required = false
        ),
        inputType = Unit.serializer(),
        outputType = String.serializer(),
        summary = "Anonymous Token",
        description = "Retrieves a token for a new, anonymous user.",
        errorCases = listOf(),
        implementation = { user: USER?, _: Unit ->
            return@typed jwtSigner().token(userAccess.idSerializer, userAccess.anonymous().let(userAccess::id))
        }
    )
}