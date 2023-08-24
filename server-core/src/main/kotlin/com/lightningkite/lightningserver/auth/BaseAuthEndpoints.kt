package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.LazyModel
import com.lightningkite.lightningserver.exceptions.ForbiddenException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.decodeUnwrappingString
import com.lightningkite.lightningserver.serialization.encodeUnwrappingString
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.typed.ApiExample
import com.lightningkite.lightningserver.typed.typed
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
open class BaseAuthEndpoints<USER : Any, ID>(
    path: ServerPath,
    val userAccess: UserAccess<USER, ID>,
    val idType: AuthType,
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

    val typeName = userAccess.authRequirement.type.classifier?.toString()?.substringAfterLast('.') ?: "Unknown"
    val jwtPrefix = "$typeName|"
    val lazyType = AuthType(LazyModel::class, listOf(userAccess.authRequirement.type, idType))
    init {
        val jwtAuthHeader = authentication(JwtAuthenticationMethod(priority = 5, fromStringInRequest = Authentication.FromStringInRequest.AuthorizationHeader(), hasher = hasher))
        val jwtAuthCookie = authentication(JwtAuthenticationMethod(priority = 1, fromStringInRequest = Authentication.FromStringInRequest.AuthorizationCookie(), hasher = hasher))
        val jwtQueryParam = authentication(JwtAuthenticationMethod(priority = 10, fromStringInRequest = Authentication.FromStringInRequest.QueryParameter("jwt"), hasher = hasher))
        authentication<JwtClaims, LazyModel<USER, ID>>(sourceType = AuthType<JwtClaims>(), destType = lazyType) {
            val sub = it.sub ?: return@authentication null
            if(!sub.startsWith(jwtPrefix)) return@authentication null
            val id = Serialization.json.decodeUnwrappingString(userAccess.idSerializer, sub.removePrefix(jwtPrefix))
            LazyModel(id, userAccess::byId)
        }
        authentication<LazyModel<USER, ID>, USER>(sourceType = lazyType, destType = userAccess.authRequirement.type) { it.value.await() }
    }

    init {
        path.docName = "Auth"
    }

    /**
     * Creates a JWT representing the given [user].
     */
    suspend fun token(user: USER, expireDuration: Duration = expiration): String = tokenById(userAccess.id(user), expireDuration)

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
        it.handleToken(tokenById(it.auth<LazyModel<USER, ID>>(lazyType)!!.value.id))
    }

    val refreshToken = path("refresh-token").get.typed(
        authRequirement = userAccess.authRequirement,
        inputType = Unit.serializer(),
        outputType = String.serializer(),
        summary = "Refresh token",
        description = "Creates a new token for the user, which can be used to authenticate with the API via the header 'Authorization: Bearer [insert token here]'.",
        errorCases = listOf(),
        examples = listOf(ApiExample(input = Unit, output = "jwt.jwt.jwt")),
        implementation = { user: USER, input: Unit ->
            token(user)
        }
    )
    val getSelf = path("self").get.typed(
        authRequirement = userAccess.authRequirement,
        inputType = Unit.serializer(),
        outputType = userAccess.serializer,
        summary = "Get Self",
        description = "Retrieves the user that you currently authenticated as.",
        errorCases = listOf(),
        implementation = { user: USER, _: Unit -> user }
    )
    val anonymous = path("anonymous").get.typed(
        authRequirement = AuthRequirement<Unit>(),
        inputType = Unit.serializer(),
        outputType = String.serializer(),
        summary = "Anonymous Token",
        description = "Creates a token for a new, anonymous user.  The token can be used to authenticate with the API via the header 'Authorization: Bearer [insert token here]",
        errorCases = listOf(),
        examples = listOf(ApiExample(input = Unit, output = "jwt.jwt.jwt")),
        implementation = { user: Unit, _: Unit ->
            return@typed token(userAccess.anonymous())
        }
    )
}