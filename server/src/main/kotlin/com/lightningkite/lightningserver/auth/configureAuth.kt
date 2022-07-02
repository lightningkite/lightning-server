package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.HtmlDefaults
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.database
import com.lightningkite.lightningserver.email.email
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningserver.typed.typed
import com.lightningkite.lightningserver.websocket.WebSockets
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.reflect.typeOf

inline fun <reified T> HttpRequest.jwt(): T? = jwt(Serialization.module.serializer())
fun <T> HttpRequest.jwt(serializer: KSerializer<T>): T? =
    (headers[HttpHeader.Authorization]?.removePrefix("Bearer ") ?: headers.cookies[HttpHeader.Authorization]?.removePrefix("Bearer "))?.let {
        try {
            AuthSettings.instance.verify<T>(serializer, it)
        } catch(e: UnauthorizedException) {
            throw UnauthorizedException(
                body = e.body,
                headers = {
                    setCookie(HttpHeader.Authorization, "deleted", maxAge = 0)
                },
                cause = e.cause
            )
        }
    }

data class AuthInfo<USER>(
    val checker: suspend (Any?)->USER,
    val type: String? = null,
    val required: Boolean = false,
)
inline fun <reified USER> AuthInfo() = if(USER::class == Unit::class) AuthInfo<USER>(checker = { Unit as USER }, type = null, required = false)
else AuthInfo<USER>(
    checker = { raw ->
        raw?.let { it as? USER } ?: try {
            raw as USER
        } catch(e: Exception) {
            throw UnauthorizedException(
                if(raw == null) "You need to be authorized to use this." else "You need to be a ${USER::class.simpleName} to use this.",
                cause = e
            )
        }
    },
    type = typeOf<USER>().toString().substringBefore('<').substringAfterLast('.'),
    required = !typeOf<USER>().isMarkedNullable
)
typealias TypeCheckOrUnauthorized<USER> = HttpRequest.()->USER

private var authorizationMethodImpl: suspend (HttpRequest) -> Any? = { null }
var Http.authorizationMethod: suspend (HttpRequest) -> Any?
    get() = authorizationMethodImpl
    set(value) {
        authorizationMethodImpl = value
    }

suspend fun HttpRequest.rawUser(): Any? = Http.authorizationMethod(this)
suspend inline fun <reified USER> HttpRequest.user(): USER {
    val raw = Http.authorizationMethod(this)
    raw?.let { it as? USER }?.let { return it }
    try {
        return raw as USER
    } catch(e: Exception) {
        throw UnauthorizedException(
            if(raw == null) "You need to be authorized to use this." else "You need to be a ${USER::class.simpleName} to use this.",
            cause = e
        )
    }
}

private var wsAuthorizationMethodImpl: suspend (WebSockets.ConnectEvent) -> Any? = { null }
var WebSockets.authorizationMethod: suspend (WebSockets.ConnectEvent) -> Any?
    get() = wsAuthorizationMethodImpl
    set(value) {
        wsAuthorizationMethodImpl = value
    }

suspend fun WebSockets.ConnectEvent.rawUser(): Any? = WebSockets.authorizationMethod(this)
suspend inline fun <reified USER> WebSockets.ConnectEvent.user(): USER {
    val raw = WebSockets.authorizationMethod(this)
    raw?.let { it as? USER }?.let { return it }
    try {
        return raw as USER
    } catch(e: Exception) {
        throw UnauthorizedException(
            if(raw == null) "You need to be authorized to use this." else "You need to be a ${USER::class.simpleName} to use this.",
            cause = e
        )
    }
}


/**
 * A Shortcut function to define authentication on the server. This will set up magic link login, no passwords.
 * This will set up JWT authentication using quickJwt.
 * It will setup routing for: emailMagicLinkEndpoint, oauthGoogle, oauthGithub, oauthApple, refreshTokenEndpoint, and self
 * It will handle getting the user by their ID or their email.
 *
 * @param path The path you wish all endpoints to be prefixed with
 * @param onNewUser An optional lambda that returns a new user provided an email. This allows quick user creation if a login email is requested but the email has not been used before.
 * @param landing The url you wish users to be sent to in their login emails.
 * @param emailSubject The subject of the login emails that will be sent out.
 * @param template A lambda to return what the email to send will be given the email and the login link.
 */

inline fun <reified USER, reified ID : Comparable<ID>> ServerPath.authEndpoints(
    crossinline onNewUser: suspend (email: String) -> USER? = { null },
    landing: String = GeneralServerSettings.instance.publicUrl,
    emailSubject: String = "${GeneralServerSettings.instance.projectName} Log In",
    noinline template: (suspend (email: String, link: String) -> String) = HtmlDefaults.defaultLoginEmailTemplate
) where USER : HasEmail, USER : HasId<ID> = authEndpoints(
    userId = { it._id },
    userById = {
        database.collection<USER>().get(it)!!
    },
    userByEmail = {
        database.collection<USER>().find(Condition.OnField(HasEmailFields.email<USER>(), Condition.Equal(it)))
            .singleOrNull() ?: onNewUser(it)?.let { database.collection<USER>().insertOne(it) }
        ?: throw NotFoundException()
    },
    landing = landing,
    emailSubject = emailSubject,
    template = template
)


/**
 * A Shortcut function to define authentication on the server. This will set up magic link login, no passwords.
 * This will set up JWT authentication using quickJwt.
 * It will setup routing for: emailMagicLinkEndpoint, oauthGoogle, oauthGithub, oauthApple, refreshTokenEndpoint, and self
 *
 * @param path The path you wish all endpoints to be prefixed with
 * @param userById A lambda that should return the user being authenticated by their id.
 * @param userByEmail A lambda that should return the user being authenticated by their email.
 * @param landing The url you wish users to be sent to in their login emails.
 * @param emailSubject The subject of the login emails that will be sent out.
 * @param template A lambda to return what the email to send will be given the email and the login link.
 */
inline fun <reified USER: Any, reified ID> ServerPath.authEndpoints(
    crossinline userId: (user: USER) -> ID,
    crossinline userById: suspend (id: ID) -> USER,
    crossinline userByEmail: suspend (email: String) -> USER,
    landing: String = GeneralServerSettings.instance.publicUrl,
    emailSubject: String = "${GeneralServerSettings.instance.projectName} Log In",
    noinline template: (suspend (email: String, link: String) -> String) = HtmlDefaults.defaultLoginEmailTemplate
): ServerPath {
    Http.authorizationMethod = {
        it.jwt<ID>()?.let { userById(it) }
    }
    WebSockets.authorizationMethod = {
        it.queryParameter("jwt")?.let { AuthSettings.instance.verify<ID>(it) }?.let { userById(it) }
    }

    docName = "Auth"
    val landingRoute: HttpRoute = get("login-landing")
    landingRoute.handler { call ->
        val token = call.queryParameter("jwt")!!
        HttpResponse.redirectToGet(
            to = call.queryParameter("destination") ?: landing,
            headers = {
                setCookie(HttpHeader.Authorization, token)
            }
        )
    }
    post("login-email").typed(
        summary = "Email Login Link",
        description = "Sends a login email to the given address",
        errorCases = listOf(),
        successCode = HttpStatus.NoContent,
        implementation = { user: Unit, address: String ->
            val jwt = AuthSettings.instance.token(userByEmail(address).let(userId), AuthSettings.instance.jwtEmailExpirationMilliseconds)
            val link = "${GeneralServerSettings.instance.publicUrl}${landingRoute.path}?jwt=$jwt"
            email.send(
                subject = emailSubject,
                to = listOf(address),
                message = "Log in to ${GeneralServerSettings.instance.projectName} as ${address}:\n$link",
                htmlMessage = template(address, link)
            )
            Unit
        }
    )
    get("refresh-token").typed(
        summary = "Refresh token",
        description = "Retrieves a new token for the user.",
        errorCases = listOf(),
        implementation = { user: USER, input: Unit ->
            AuthSettings.instance.token(user.let(userId))
        }
    )
    get("self").typed(
        summary = "Get Self",
        description = "Retrieves the user that you currently are",
        errorCases = listOf(),
        implementation = { user: USER, _: Unit -> user }
    )
    oauthGoogle(landingRoute = landingRoute) { userByEmail(it).let(userId).toString() }
    oauthGithub(landingRoute = landingRoute) { userByEmail(it).let(userId).toString() }
    oauthApple(landingRoute = landingRoute) { userByEmail(it).let(userId).toString() }
    return this
}

//TODO: Move?
@Serializable
data class EmailRequest(val email: String)
