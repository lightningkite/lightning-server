package com.lightningkite.ktorbatteries.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTCreator
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import com.lightningkite.ktorbatteries.db.database
import com.lightningkite.ktorbatteries.email.Attachment
import com.lightningkite.ktorbatteries.email.EmailSettings
import com.lightningkite.ktorbatteries.email.email
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import com.lightningkite.ktorbatteries.typed.BoxPrincipal
import com.lightningkite.ktorbatteries.typed.get
import com.lightningkite.ktorbatteries.typed.parseUrlPartOrBadRequest
import com.lightningkite.ktorbatteries.typed.post
import com.lightningkite.ktordb.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.*
import io.ktor.http.*
import io.ktor.http.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.serialization.Serializable
import java.util.*

inline fun <reified USER, reified ID : Comparable<ID>> Application.configureAuth(
    path: String = "auth",
    crossinline onNewUser: suspend (email: String) -> USER? = { null },
    landing: String = GeneralServerSettings.instance.publicUrl,
    emailSubject: String = "${GeneralServerSettings.instance.projectName} Log In",
    noinline template: (suspend (email: String, link: String) -> String) = defaultLoginEmailTemplate
) where USER : HasEmail, USER : HasId<ID> = configureAuth(
    path = path,
    userById = {
        println("ID string is $it")
        println("ID string is ${it.parseUrlPartOrBadRequest<UUID>()}")
        database.collection<USER>().get(it.parseUrlPartOrBadRequest<ID>().also { println("Id is $it") })!!
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

inline fun <reified USER : HasId<ID>, reified ID : Comparable<ID>> Application.configureAuth(
    path: String = "auth",
    crossinline userById: suspend (id: String) -> USER,
    crossinline userByEmail: suspend (id: String) -> USER,
    landing: String = GeneralServerSettings.instance.publicUrl,
    emailSubject: String = "${GeneralServerSettings.instance.projectName} Log In",
    noinline template: (suspend (email: String, link: String) -> String) = defaultLoginEmailTemplate
) {
    authentication {
        quickJwt { creds ->
            BoxPrincipal(
                userById(
                    creds.payload
                        .getClaim(AuthSettings.userIdKey)
                        .asString()
                        .also { println("ID is ${it}") }
                )
            )
                .also { println("Principal is ${it}") }
        }
    }
    routing {
        route(path) {
            emailMagicLinkEndpoint(
                emailToId = { userByEmail(it)._id.toString() },
                emailSubject = emailSubject,
                template = template,
                landing = landing
            )
            refreshTokenEndpoint<USER, ID>()
            oauthGoogle() { userByEmail(it)._id.toString() }
            oauthGithub() { userByEmail(it)._id.toString() }
            oauthApple() { userByEmail(it)._id.toString() }
        }
    }
}

/**
Handles the setup and main verification for jwt token authentication.
The only thing required from the user is to provide a Principal object.
The user can also do any other verification they may need at the same time.
A return of null from validate will result in authentication failure.
 */
fun AuthenticationConfig.quickJwt(
    validate: suspend ApplicationCall.(JWTCredential) -> Principal?,
) {
    jwt {
        realm = AuthSettings.instance.jwtRealm
        authHeader {
            val token = it.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")
                ?: run {
                    val value = it.request.queryParameters["jwt"]
                    if (value != null) {
                        it.response.header(
                            "Set-Cookie", renderSetCookieHeader(
                                name = HttpHeaders.Authorization,
                                value = value,
                                domain = AuthSettings.instance.authDomain,
                                secure = it.request.headers["X-Scheme"]?.contains("https") == true,
                                extensions = mapOf("SameSite" to "Lax")
                            )
                        )
                    }
                    value
                }
                ?: it.request.cookies[HttpHeaders.Authorization]
                ?: return@authHeader null
            HttpAuthHeader.Single(AuthScheme.Bearer, token)
        }
        verifier(
            JWT
                .require(Algorithm.HMAC256(AuthSettings.instance.jwtSecret))
                .withAudience(AuthSettings.instance.jwtAudience)
                .withIssuer(AuthSettings.instance.jwtIssuer)
                .build()
        )
        validate { credential: JWTCredential ->
            validate(credential)
        }
    }
}

fun makeToken(id: String, expiration: Long? = null): String {
    return JWT.create()
        .withAudience(AuthSettings.instance.jwtAudience)
        .withIssuer(AuthSettings.instance.jwtIssuer)
        .withClaim(AuthSettings.userIdKey, id)
        .withIssuedAt(Date())
        .let {
            (expiration ?: AuthSettings.instance.jwtExpirationMilliseconds)?.let { exp ->
                it.withExpiresAt(Date(System.currentTimeMillis() + exp))
            } ?: it
        }
        .sign(Algorithm.HMAC256(AuthSettings.instance.jwtSecret))
}

fun makeToken(additionalSetup: JWTCreator.Builder.() -> JWTCreator.Builder = { this }): String {
    return JWT.create()
        .withAudience(AuthSettings.instance.jwtAudience)
        .withIssuer(AuthSettings.instance.jwtIssuer)
        .withIssuedAt(Date())
        .let(additionalSetup)
        .sign(Algorithm.HMAC256(AuthSettings.instance.jwtSecret))
}

fun checkToken(token: String): DecodedJWT? = try {
    JWT
        .require(Algorithm.HMAC256(AuthSettings.instance.jwtSecret))
        .withAudience(AuthSettings.instance.jwtAudience)
        .withIssuer(AuthSettings.instance.jwtIssuer)
        .build()
        .verify(token)
} catch (e: JWTVerificationException) {
    null
}

//TODO: Move?
@Serializable
data class EmailRequest(val email: String)

val defaultLoginEmailTemplate: (suspend (email: String, link: String) -> String) = { email: String, link: String ->
    """
        <!DOCTYPE html>
        <html>
        <body>
        <p>We received a request for a login email for ${email}. To log in, please click the link below.</p>
        <a href="$link">Click here to login</a>
        <p>If you did not request to be logged in, you can simply ignore this email.</p>
        <h3>${GeneralServerSettings.instance.projectName}</h3>
        </body>
        </html>
        """.trimIndent()
}

@KtorDsl
fun Route.emailMagicLinkEndpoint(
    path: String = "login-email",
    emailToId: suspend (email: String) -> String,
    landing: String = GeneralServerSettings.instance.publicUrl,
    emailSubject: String = "${GeneralServerSettings.instance.projectName} Log In",
    template: (suspend (email: String, link: String) -> String) = defaultLoginEmailTemplate
) = emailMagicLinkEndpoint(
    path = path,
    makeLink = { landing + "?jwt=${makeToken(emailToId(it))}" },
    emailSubject = emailSubject,
    template = template
)

@KtorDsl
fun Route.emailMagicLinkEndpoint(
    path: String = "login-email",
    makeLink: suspend (email: String) -> String,
    emailSubject: String = "${GeneralServerSettings.instance.projectName} Log In",
    template: (suspend (email: String, link: String) -> String) = defaultLoginEmailTemplate
) {
    post(
        path = path,
        summary = "Email Login Link",
        description = "Sends a login email to the given address",
        errorCases = listOf(),
        successCode = HttpStatusCode.NoContent,
        implementation = { address: String ->
            val link = makeLink(address)
            email.send(
                subject = emailSubject,
                to = listOf(address),
                message = "Log in to ${GeneralServerSettings.instance.projectName} as ${address}:\n$link",
                htmlMessage = template(address, link)
            )
            Unit
        }
    )
}

@KtorDsl
inline fun <reified USER : HasId<ID>, reified ID : Comparable<ID>> Route.refreshTokenEndpoint(path: String = "refresh-token") =
    refreshTokenEndpoint<USER>(path) { makeToken(it._id.toString()) }

@KtorDsl
inline fun <reified USER> Route.refreshTokenEndpoint(
    path: String = "refresh-token",
    crossinline principalToToken: suspend (USER) -> String
) {
    get(
        path = path,
        summary = "Retrieves a new token for the user.",
        errorCases = listOf(),
        implementation = { user: USER?, input: Unit ->
            if (user == null) throw BadRequestException("You are not authenticated.")
            principalToToken(user)
        }
    )
}

@Deprecated("Use the new format instead")
fun Route.refreshToken(path: String = "refresh-token", idFromPrincipal: (Principal) -> String) {
    post(path) {
        val userId = call.principal<Principal>()!!.let(idFromPrincipal)
        val token = makeToken(userId)
        call.respond(token)
    }
}

@Deprecated("Use the new format instead")
fun Route.logOut(path: String = "logout") {
    get(path) {
        call.response.cookies.appendExpired(HttpHeaders.Authorization)
        call.respond(HttpStatusCode.NoContent)
    }
}

@Deprecated("Use the new format instead")
fun Route.emailMagicLink(
    path: String = "login-email",
    emailSubject: String = "${GeneralServerSettings.instance.projectName} Log In",
    emailTemplate: suspend (email: String, token: String) -> String = { email, token ->
        """
        We received a request for a login email for ${email}. To log in, please click the link the link below.
        
        ${GeneralServerSettings.instance.publicUrl}/landing?jwt=$token
        
        If you did not request to be logged in, you can simply ignore this email.
        
        ${GeneralServerSettings.instance.projectName}
        """.trimIndent()
    },
    emailHtmlTemplate: (suspend (email: String, token: String) -> String)? = { email, token ->
        """
        <!DOCTYPE html>
        <html>
        <body>
        <p>We received a request for a login email for ${email}. To log in, please click the link below.</p>
        <a href="${GeneralServerSettings.instance.publicUrl}/landing?jwt=$token">Click here to login</a>
        <p>If you did not request to be logged in, you can simply ignore this email.</p>
        <h3>${GeneralServerSettings.instance.projectName}</h3>
        </body>
        </html>
        """.trimIndent()
    },
    emailAttachments: List<Attachment> = listOf(),
    returnIfUserExists: Boolean = false,
    getUserIdByEmail: suspend (email: String) -> String?
) {
    post(path) {
        val emailRequest = call.receive<EmailRequest>()
        getUserIdByEmail(emailRequest.email)?.let { userId ->
            val token = makeToken(userId)
            EmailSettings.instance.emailClient.send(
                subject = emailSubject,
                to = listOf(emailRequest.email),
                message = emailTemplate(emailRequest.email, token),
                htmlMessage = emailHtmlTemplate?.let { it(emailRequest.email, token) },
                attachments = emailAttachments
            )
            call.respond(HttpStatusCode.NoContent)
        } ?: call.respond(if (returnIfUserExists) HttpStatusCode.NotFound else HttpStatusCode.NoContent)
    }
}
