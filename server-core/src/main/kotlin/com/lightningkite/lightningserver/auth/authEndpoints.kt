package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.HtmlDefaults
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.email.EmailClient
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.typed.typed
import com.lightningkite.lightningserver.websocket.WebSockets
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.serialization.Serializable
import java.time.Duration


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
    noinline handleToken: suspend HttpRequest.(token: String)->HttpResponse = { token ->
        HttpResponse.redirectToGet(
            to = queryParameter("destination") ?: landing,
            headers = {
                setCookie(HttpHeader.Authorization, token)
            }
        )
    },
    noinline emailSubject: ()->String = { "${generalSettings().projectName} Log In" },
    noinline template: (suspend (email: String, link: String) -> String) = HtmlDefaults.defaultLoginEmailTemplate
) where USER : HasEmail, USER : HasId<ID> = authEndpoints(
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
inline fun <reified USER: Any, reified ID> ServerPath.authEndpoints(
    noinline jwtSigner: () -> JwtSigner,
    noinline email: () -> EmailClient,
    crossinline userId: (user: USER) -> ID,
    crossinline userById: suspend (id: ID) -> USER,
    crossinline userByEmail: suspend (email: String) -> USER,
    landing: String = "/",
    noinline handleToken: suspend HttpRequest.(token: String)->HttpResponse = { token ->
        HttpResponse.redirectToGet(
            to = queryParameter("destination") ?: landing,
            headers = {
                setCookie(HttpHeader.Authorization, token)
            }
        )
    },
    crossinline emailSubject: ()->String = { "${generalSettings().projectName} Log In" },
    noinline template: (suspend (email: String, link: String) -> String) = HtmlDefaults.defaultLoginEmailTemplate
): ServerPath {
    Http.authorizationMethod = {
        it.jwt<ID>(jwtSigner())?.let { userById(it) }
    }
    WebSockets.authorizationMethod = {
        it.queryParameter("jwt")?.let { jwtSigner().verify<ID>(it) }?.let { userById(it) }
    }

    docName = "Auth"
    val landingRoute: HttpEndpoint = get("login-landing")
    landingRoute.handler {
        val token = it.queryParameter("jwt")!!
        it.handleToken(token)
    }
    post("login-email").typed(
        summary = "Email Login Link",
        description = "Sends a login email to the given address",
        errorCases = listOf(),
        successCode = HttpStatus.NoContent,
        implementation = { user: Unit, address: String ->
            val jwt = jwtSigner().token(
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
    get("refresh-token").typed(
        summary = "Refresh token",
        description = "Retrieves a new token for the user.",
        errorCases = listOf(),
        implementation = { user: USER, input: Unit ->
            jwtSigner().token(user.let(userId))
        }
    )
    get("self").typed(
        summary = "Get Self",
        description = "Retrieves the user that you currently are",
        errorCases = listOf(),
        implementation = { user: USER, _: Unit -> user }
    )
    oauthGoogle(jwtSigner = jwtSigner, landingRoute = landingRoute) { userByEmail(it).let(userId).toString() }
    oauthGithub(jwtSigner = jwtSigner, landingRoute = landingRoute) { userByEmail(it).let(userId).toString() }
    oauthApple(jwtSigner = jwtSigner, landingRoute = landingRoute) { userByEmail(it).let(userId).toString() }
    return this
}

@LightningServerDsl
fun ServerPath.authEndpointExtensionHtml(): ServerPath {
    val loginEmail = post("login-email")
    get("login-email/").handler {
        HttpResponse(
            body = HttpContent.Text(
                string = HtmlDefaults.basePage("""
                    <form action='form-post/' enctype='application/x-www-form-urlencoded' method='post'>
                        <p>Log in or sign up via email magic link</p>
                        <input type='email' name='email'/>
                        <button type='submit'>Submit</button>
                    </form>
                """.trimIndent()),
                type = ContentType.Text.Html
            )
        )
    }
    post("login-email/form-post/").handler {
        val basis = Http.endpoints[loginEmail]!!(it.copy(body = HttpContent.Text("\"${it.queryParameter("email")}\"", ContentType.Application.Json)))
        if(basis.status.success) {
            HttpResponse(
                body = HttpContent.Text(
                    string = HtmlDefaults.basePage("""
                    <p>Success!  An email has been sent with a link to log in.</p>
                """.trimIndent()),
                    type = ContentType.Text.Html
                )
            )
        } else {
            basis
        }
    }
    return this
}