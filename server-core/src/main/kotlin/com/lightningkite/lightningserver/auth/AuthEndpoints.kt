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


@Serializable
data class SSOAuthSubmission(
    val userKey: String,
    val clientKey: UUID,
)

@Serializable
data class SSOCredentials<ID>(
    val userId: ID,
    val userKey: String,
    val clientKey: UUID,
)

private const val availableNumbers = "1234567890"
val smsTemplate:suspend (code: String) -> String = { code -> "Your ${generalSettings().projectName} code is ${code}. Don't share this with anyone." }

open class SMSAuthEndpoints<USER, ID>(
    path: ServerPath,
    private val idSerializer: KSerializer<ID>,
    private val jwtSigner: () -> JwtSigner,
    private val sms: () -> SMSClient,
    private val userByPhone: (suspend (phone: String) -> USER?),
    private val userId: (user:USER) -> ID,
    private val storeCredentials: (suspend (SSOCredentials<ID>) -> Unit),
    private val validateCredentials: (suspend (SSOAuthSubmission) -> ID?),
    private val template: (suspend (code: String) -> String) = smsTemplate
) : ServerPathGroup(path) {

    init {
        path.docName = "SMSAuth"
    }

    val loginSMS = path("login-sms").post.typed(
        summary = "Request SMS SSO",
        description = "Sends a SSO password to the given Phone Number",
        errorCases = listOf(),
        successCode = HttpStatus.OK,
        implementation = { _: Unit, phone: String ->
            val user = userByPhone(phone) ?: return@typed UUID.randomUUID()
            val userId = userId(user)
            val clientCode = UUID.randomUUID()
            val userCode = (1..7)
                .map { availableNumbers.random() }
                .joinToString("")

            storeCredentials(SSOCredentials(userId, userCode, clientCode))

            sms().send(phone, template(userCode))

            clientCode
        }
    )

    val submitLoginSSO = post("submit-sso").typed(
        summary = "Submit SMS SSO",
        description = "Submit the SSO sent to the user to finalize login.",
        errorCases = listOf(),
        implementation = { user: Unit, input: SSOAuthSubmission ->
            val id = validateCredentials(input)

            if (id != null)
                jwtSigner().token(
                    idSerializer,
                    id,
                    jwtSigner().emailExpiration
                )
            else throw BadRequestException()
        }
    )
}

inline fun <reified USER, reified ID : Comparable<ID>> ServerPath.smsAuthEndpoints(
    noinline database: () -> Database,
    noinline jwtSigner: () -> JwtSigner,
    noinline sms: () -> SMSClient,
    noinline storeCredentials: (suspend (SSOCredentials<ID>) -> Unit),
    noinline validateCredentials: (suspend (SSOAuthSubmission) -> ID?),
    noinline template: (suspend (code: String) -> String) = smsTemplate,
) where USER : HasPhoneNumber, USER : HasId<ID> = SMSAuthEndpoints(
    path = this,
    idSerializer = Serialization.module.serializer(),
    jwtSigner = jwtSigner,
    sms = sms,
    userId = {
        @Suppress("UNCHECKED_CAST")
        (it as HasId<ID>)._id
    },
    userByPhone = {
        database()
            .collection<USER>()
            .find(Condition.OnField(HasPhoneNumberFields.phoneNumber<USER>(), Condition.Equal(it)))
            .singleOrNull()
    },
    storeCredentials = storeCredentials,
    validateCredentials = validateCredentials,
    template = template,
)

inline fun <reified USER, reified ID : Comparable<ID>> ServerPath.smsAuthEndpoints(
    noinline jwtSigner: () -> JwtSigner,
    noinline sms: () -> SMSClient,
    noinline userByPhone: (phone: String) -> USER?,
    noinline userId: (user: USER) -> ID,
    noinline storeCredentials: (SSOCredentials<ID>) -> Unit,
    noinline validateCredentials: (SSOAuthSubmission) -> ID?,
    noinline template: (suspend (code: String) -> String) = smsTemplate,
) where USER : HasPhoneNumber, USER : HasId<ID> = SMSAuthEndpoints(
    path = this,
    idSerializer = Serialization.module.serializer(),
    jwtSigner = jwtSigner,
    sms = sms,
    userId = userId,
    userByPhone = userByPhone,
    storeCredentials = storeCredentials,
    validateCredentials = validateCredentials,
    template = template,
)


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


