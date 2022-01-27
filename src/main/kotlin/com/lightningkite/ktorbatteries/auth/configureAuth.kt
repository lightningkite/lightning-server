package com.lightningkite.ktorbatteries.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.lightningkite.ktorbatteries.email.Attachment
import com.lightningkite.ktorbatteries.email.EmailSettings
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.auth.jwt.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.auth.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import kotlinx.serialization.Serializable
import java.util.*

fun Authentication.Configuration.quickJwt(
    jwtChecks: (JWTCredential) -> Boolean = { true },
    idToPrincipal: suspend (String) -> Principal?
) {
    jwt {
        realm = AuthSettings.instance.jwtRealm
        authHeader {
            val token = it.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")
                ?: it.request.cookies[HttpHeaders.Authorization]
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
            if (
                credential.payload.audience.contains(AuthSettings.instance.jwtAudience) &&
                credential.payload.issuer == AuthSettings.instance.jwtIssuer &&
                jwtChecks(credential)
            ) {
                credential.payload
                    .getClaim(AuthSettings.userIdKey)
                    .asString()
                    .let { idToPrincipal(it) }
            } else null
        }
    }
}

fun makeToken(id: String): String {
    return JWT.create()
        .withAudience(AuthSettings.instance.jwtAudience)
        .withIssuer(AuthSettings.instance.jwtIssuer)
        .withClaim(AuthSettings.userIdKey, id)
        .withIssuedAt(Date())
        .let {
            AuthSettings.instance.jwtExpirationMilliseconds?.let { exp ->
                it.withExpiresAt(Date(System.currentTimeMillis() + exp))
            } ?: it
        }
        .sign(Algorithm.HMAC256(AuthSettings.instance.jwtSecret))
}

//TODO: Move?
@Serializable
data class EmailRequest(val email: String)

@Serializable
data class TokenResponse(val token: String)

fun Route.emailMagicLink(
    path: String = "login-email",
    emailSubject: String = "${GeneralServerSettings.instance.projectName} Log In",
    emailTemplate: (email: String, token: String) -> String = { email, token ->
        """
        We received a request for a login email for ${email}. To log in, please click the link the link below.
        
        ${GeneralServerSettings.instance.publicUrl}landing?jwt=$token
        
        If you did not request to be logged in, you can simply ignore this email.
        """.trimIndent()
    },
    emailHtmlTemplate: ((email: String, token: String) -> String)? = null,
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
        } ?: call.respond(if(returnIfUserExists) HttpStatusCode.NotFound else HttpStatusCode.NoContent)
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
