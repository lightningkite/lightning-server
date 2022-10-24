@file:UseContextualSerialization(UUID::class)

package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.HtmlDefaults
import com.lightningkite.lightningserver.core.*
import com.lightningkite.lightningserver.email.EmailClient
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.sms.SMSClient
import com.lightningkite.lightningserver.typed.typed
import com.lightningkite.lightningserver.websocket.WebSockets
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import java.util.*

@Deprecated("Use the new auth endpoint sets instead")
open class AuthEndpoints<USER : Any, ID>(
    path: ServerPath,
    private val userSerializer: KSerializer<USER>,
    private val authInfo: AuthInfo<USER>,
    private val idSerializer: KSerializer<ID>,
    private val jwtSigner: () -> JwtSigner,
    private val email: () -> EmailClient,
    private val userId: (user: USER) -> ID,
    private val userById: suspend (id: ID) -> USER,
    private val userByEmail: suspend (email: String) -> USER,
    private val landing: String = "/",
    private val handleToken: suspend HttpRequest.(token: String) -> HttpResponse = { token ->
        HttpResponse.redirectToGet(
            to = queryParameter("destination") ?: landing,
            headers = {
                setCookie(HttpHeader.Authorization, token)
            }
        )
    },
    private val emailSubject: () -> String = { "${generalSettings().projectName} Log In" },
    private val template: (suspend (email: String, link: String) -> String) = HtmlDefaults.defaultLoginEmailTemplate
) : ServerPathGroup(path) {
    init {
        Authorization.handler = object : Authorization.Handler<USER> {
            override suspend fun http(request: HttpRequest): USER? {
                return request.jwt<ID>(jwtSigner(), idSerializer)?.let { userById(it) }
            }

            override suspend fun ws(request: WebSockets.ConnectEvent): USER? {
                return request.jwt<ID>(jwtSigner(), idSerializer)?.let { userById(it) }
            }

            override fun userToIdString(user: USER): String =
                Serialization.json.encodeToString(idSerializer, userId(user))

            override suspend fun idStringToUser(id: String): USER =
                userById(Serialization.json.decodeFromString(idSerializer, id))
        }
        path.docName = "Auth"
    }

    val landingRoute: HttpEndpoint = path("login-landing").get.handler {
        val subject = jwtSigner().verify(idSerializer, it.queryParameter("jwt")!!)
        it.handleToken(jwtSigner().token(idSerializer, subject))
    }
    val loginEmail = path("login-email").post.typed(
        summary = "Email Login Link",
        description = "Sends a login email to the given address",
        errorCases = listOf(),
        successCode = HttpStatus.NoContent,
        implementation = { user: Unit, address: String ->
            val jwt = jwtSigner().token(
                idSerializer,
                userByEmail(address).let(userId),
                jwtSigner().emailExpiration
            )
            val link = "${generalSettings().publicUrl}${landingRoute.path}?jwt=$jwt"
            email().send(
                subject = emailSubject(),
                to = listOf(address),
                message = "Log in to ${generalSettings().projectName} as ${address}:\n$link",
                htmlMessage = template(address, link)
            )
            Unit
        }
    )
    val refreshToken = path("refresh-token").get.typed(
        authInfo = authInfo,
        inputType = Unit.serializer(),
        outputType = String.serializer(),
        summary = "Refresh token",
        description = "Retrieves a new token for the user.",
        errorCases = listOf(),
        implementation = { user: USER, input: Unit ->
            jwtSigner().token(idSerializer, user.let(userId))
        }
    )
    val getSelf = path("self").get.typed(
        authInfo = authInfo,
        inputType = Unit.serializer(),
        outputType = userSerializer,
        summary = "Get Self",
        description = "Retrieves the user that you currently are",
        errorCases = listOf(),
        implementation = { user: USER, _: Unit -> user }
    )
    val oauthGoogle = OauthGoogleEndpoints(path = path("oauth/google"), jwtSigner = jwtSigner, landing = landingRoute) {
        userByEmail(it).let(userId).toString()
    }
    val oauthGithub = OauthGitHubEndpoints(path = path("oauth/github"), jwtSigner = jwtSigner, landing = landingRoute) {
        userByEmail(it).let(userId).toString()
    }
    val oauthApple = OauthAppleEndpoints(
        path = path("oauth/apple"),
        jwtSigner = jwtSigner,
        landing = landingRoute
    ) { userByEmail(it).let(userId).toString() }
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
@Deprecated("Use the new auth endpoint sets instead")
@LightningServerDsl
inline fun <reified USER, reified ID : Comparable<ID>> ServerPath.authEndpoints(
    noinline jwtSigner: () -> JwtSigner,
    noinline database: () -> Database,
    noinline email: () -> EmailClient,
    crossinline onNewUser: suspend (email: String) -> USER? = { null },
    landing: String = "/",
    noinline handleToken: suspend HttpRequest.(token: String) -> HttpResponse = { token ->
        HttpResponse.redirectToGet(
            to = queryParameter("destination") ?: landing,
            headers = {
                setCookie(HttpHeader.Authorization, token)
            }
        )
    },
    noinline emailSubject: () -> String = { "${generalSettings().projectName} Log In" },
    noinline template: (suspend (email: String, link: String) -> String) = HtmlDefaults.defaultLoginEmailTemplate
) where USER : HasEmail, USER : HasId<ID> = AuthEndpoints(
    path = this,
    userSerializer = Serialization.module.serializer(),
    idSerializer = Serialization.module.serializer(),
    authInfo = AuthInfo<USER>(),
    jwtSigner = jwtSigner,
    email = email,
    userId = {
        @Suppress("UNCHECKED_CAST")
        (it as HasId<ID>)._id
    },
    userById = {
        database().collection<USER>().get(it)!!
    },
    userByEmail = {
        database().collection<USER>().find(Condition.OnField(HasEmailFields.email<USER>(), Condition.Equal(it)))
            .singleOrNull() ?: onNewUser(it)?.let { database().collection<USER>().insertOne(it) }
        ?: throw NotFoundException()
    },
    landing = landing,
    handleToken = handleToken,
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
@Deprecated("Use the new auth endpoint sets instead")
@LightningServerDsl
inline fun <reified USER : Any, reified ID> ServerPath.authEndpoints(
    noinline jwtSigner: () -> JwtSigner,
    noinline email: () -> EmailClient,
    noinline userId: (user: USER) -> ID,
    noinline userById: suspend (id: ID) -> USER,
    noinline userByEmail: suspend (email: String) -> USER,
    landing: String = "/",
    noinline handleToken: suspend HttpRequest.(token: String) -> HttpResponse = { token ->
        HttpResponse.redirectToGet(
            to = queryParameter("destination") ?: landing,
            headers = {
                setCookie(HttpHeader.Authorization, token)
            }
        )
    },
    noinline emailSubject: () -> String = { "${generalSettings().projectName} Log In" },
    noinline template: (suspend (email: String, link: String) -> String) = HtmlDefaults.defaultLoginEmailTemplate
) = AuthEndpoints(
    path = this,
    userSerializer = Serialization.module.serializer(),
    idSerializer = Serialization.module.serializer(),
    authInfo = AuthInfo<USER>(),
    jwtSigner = jwtSigner,
    email = email,
    userId = userId,
    userById = userById,
    userByEmail = userByEmail,
    landing = landing,
    handleToken = handleToken,
    emailSubject = emailSubject,
    template = template
)

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
@Deprecated("Use the new auth endpoint sets instead")
inline fun <reified USER, reified ID : Comparable<ID>> AuthEndpoints(
    path: ServerPath,
    noinline jwtSigner: () -> JwtSigner,
    noinline database: () -> Database,
    noinline email: () -> EmailClient,
    crossinline onNewUser: suspend (email: String) -> USER? = { null },
    landing: String = "/",
    noinline handleToken: suspend HttpRequest.(token: String) -> HttpResponse = { token ->
        HttpResponse.redirectToGet(
            to = queryParameter("destination") ?: landing,
            headers = {
                setCookie(HttpHeader.Authorization, token)
            }
        )
    },
    noinline emailSubject: () -> String = { "${generalSettings().projectName} Log In" },
    noinline template: (suspend (email: String, link: String) -> String) = HtmlDefaults.defaultLoginEmailTemplate
) where USER : HasEmail, USER : HasId<ID> = AuthEndpoints(
    path = path,
    userSerializer = Serialization.module.serializer(),
    idSerializer = Serialization.module.serializer(),
    authInfo = AuthInfo<USER>(),
    jwtSigner = jwtSigner,
    email = email,
    userId = {
        @Suppress("UNCHECKED_CAST")
        (it as HasId<ID>)._id
    },
    userById = {
        database().collection<USER>().get(it)!!
    },
    userByEmail = {
        database().collection<USER>().find(Condition.OnField(HasEmailFields.email<USER>(), Condition.Equal(it)))
            .singleOrNull() ?: onNewUser(it)?.let { database().collection<USER>().insertOne(it) }
        ?: throw NotFoundException()
    },
    landing = landing,
    handleToken = handleToken,
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
@Deprecated("Use the new auth endpoint sets instead")
inline fun <reified USER : Any, reified ID> AuthEndpoints(
    path: ServerPath,
    noinline jwtSigner: () -> JwtSigner,
    noinline email: () -> EmailClient,
    noinline userId: (user: USER) -> ID,
    noinline userById: suspend (id: ID) -> USER,
    noinline userByEmail: suspend (email: String) -> USER,
    landing: String = "/",
    noinline handleToken: suspend HttpRequest.(token: String) -> HttpResponse = { token ->
        HttpResponse.redirectToGet(
            to = queryParameter("destination") ?: landing,
            headers = {
                setCookie(HttpHeader.Authorization, token)
            }
        )
    },
    noinline emailSubject: () -> String = { "${generalSettings().projectName} Log In" },
    noinline template: (suspend (email: String, link: String) -> String) = HtmlDefaults.defaultLoginEmailTemplate
) = AuthEndpoints(
    path = path,
    userSerializer = Serialization.module.serializer(),
    idSerializer = Serialization.module.serializer(),
    authInfo = AuthInfo<USER>(),
    jwtSigner = jwtSigner,
    email = email,
    userId = userId,
    userById = userById,
    userByEmail = userByEmail,
    landing = landing,
    handleToken = handleToken,
    emailSubject = emailSubject,
    template = template
)


