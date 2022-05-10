package com.lightningkite.ktorbatteries.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTCreator
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import com.lightningkite.ktorbatteries.client
import com.lightningkite.ktorbatteries.email.Attachment
import com.lightningkite.ktorbatteries.email.EmailSettings
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.*
import io.ktor.http.*
import io.ktor.http.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.*

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

fun makeToken(additionalSetup: JWTCreator.Builder.()->JWTCreator.Builder = { this }): String {
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
} catch(e: JWTVerificationException) { null }

//TODO: Move?
@Serializable
data class EmailRequest(val email: String)

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

fun Route.refreshToken(path: String = "refresh-token", idFromPrincipal: (Principal) -> String) {
    post(path) {
        val userId = call.principal<Principal>()!!.let(idFromPrincipal)
        val token = makeToken(userId)
        call.respond(token)
    }
}

fun Route.logOut(path: String = "logout") {
    get(path) {
        call.response.cookies.appendExpired(HttpHeaders.Authorization)
        call.respond(HttpStatusCode.NoContent)
    }
}

/*

By linking up database with users, we could get this setup going:

routing {
    authUrls(User.table, database)
}

data class User(...): Principal, HasEmail, HasPhoneNumber

 */