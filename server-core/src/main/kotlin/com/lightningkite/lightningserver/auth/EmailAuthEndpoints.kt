package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.HtmlDefaults
import com.lightningkite.lightningserver.cache.CacheInterface
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.set
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.email.EmailClient
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.typed.typed
import java.net.URLDecoder
import java.security.SecureRandom
import java.time.Duration

open class EmailAuthEndpoints<USER : Any, ID>(
    val base: BaseAuthEndpoints<USER, ID>,
    val emailAccess: UserEmailAccess<USER, ID>,
    private val cache: () -> CacheInterface,
    private val email: () -> EmailClient,
    private val emailSubject: () -> String = { "${generalSettings().projectName} Log In" },
    private val template: (suspend (email: String, link: String, pin: String) -> String) = { email, link, pin ->
        HtmlDefaults.baseEmail("""
        <table role="presentation" style="width:100%;border-collapse:collapse;border:0;border-spacing:0;">
            ${
            HtmlDefaults.logo?.let {
                """
                    <tr><td align="center" style="padding:16px;"><img src="$it" alt="${generalSettings().projectName}"/></td></tr>
                """.trimIndent()
            } ?: ""
        }
            <tr><td align="center" style="padding:0px;"><h1>Log In to ${generalSettings().projectName}</h1></td></tr>
            <tr><td align="center" style="padding:0px;"><p>We received a request for a login email for ${email}. To log in, please click the link below or enter the PIN.</p></td></tr>
            <tr><td align="center" style="padding:0px;">
                <table>
                    <tr><td align="center" style="padding:16px;background-color: ${HtmlDefaults.primaryColor};border-radius: 8px"><a style="color:white;text-decoration: none;font-size: 22px;" href="$link">Click here to login</a></td></tr>
                </table>
            </td></tr>
            <tr><td align="center" style="padding:0px;"><h2>PIN: $pin</h2></td></tr>
            <tr><td align="center" style="padding:0px;"><p>If you did not request to be logged in, you can simply ignore this email.</p></td></tr>
            <tr><td align="center" style="padding:0px;"><h3>${generalSettings().projectName}</h3></td></tr>
        </table>
        """.trimIndent())
    },
) : ServerPathGroup(base.path) {
    private fun cacheKey(email: String): String = email + "_email_login_pin"
    val loginEmail = path("login-email").post.typed(
        summary = "Email Login Link",
        description = "Sends a login email to the given address",
        errorCases = listOf(),
        successCode = HttpStatus.NoContent,
        implementation = { user: Unit, addressUnsafe: String ->
            val address = addressUnsafe.lowercase()
            val jwt = base.jwtSigner().token(
                emailAccess.idSerializer,
                emailAccess.byEmail(address).let(emailAccess::id),
                base.jwtSigner().emailExpiration
            )
            val pin = SecureRandom().nextInt(1000000).toString().padStart(6, '0')
            cache().set(cacheKey(address), pin.secureHash(), Duration.ofMinutes(15))
            val link = "${generalSettings().publicUrl}${base.landingRoute.path}?jwt=$jwt"
            email().send(
                subject = emailSubject(),
                to = listOf(address),
                message = "Log in to ${generalSettings().projectName} as ${address}:\n$link\nPIN: $pin",
                htmlMessage = template(address, link, pin)
            )
            Unit
        }
    )
    val loginEmailPin = path("login-email-pin").post.typed(
        summary = "Email PIN Login",
        description = "Logs in to the given email with a PIN",
        errorCases = listOf(),
        successCode = HttpStatus.OK,
        implementation = { anon: Unit, input: EmailPinLogin ->
            val email = input.email.lowercase()
            val pin = cache().get<String>(cacheKey(email))
                ?: throw NotFoundException("No PIN found for email ${email}; perhaps it has expired?")
            if(!input.pin.checkHash(pin)) throw BadRequestException("Incorrect PIN")
            cache().remove(cacheKey(email))
            base.jwtSigner().token(
                emailAccess.idSerializer,
                emailAccess.byEmail(email).let(emailAccess::id),
                base.jwtSigner().expiration
            )
        }
    )
    val oauthGoogle = OauthGoogleEndpoints(path = path("oauth/google"), jwtSigner = base.jwtSigner, landing = base.landingRoute) {
        emailAccess.byEmail(it).let(emailAccess::id).toString()
    }
    val oauthGithub = OauthGitHubEndpoints(path = path("oauth/github"), jwtSigner = base.jwtSigner, landing = base.landingRoute) {
        emailAccess.byEmail(it).let(emailAccess::id).toString()
    }
    val oauthApple = OauthAppleEndpoints(
        path = path("oauth/apple"),
        jwtSigner = base.jwtSigner,
        landing = base.landingRoute
    ) { emailAccess.byEmail(it).let(emailAccess::id).toString() }

    val loginEmailHtml = path("login-email/").get.handler {
        HttpResponse(
            body = HttpContent.Text(
                string = HtmlDefaults.basePage(
                    """
                    <form action='form-post/' enctype='application/x-www-form-urlencoded' method='post'>
                        <p>Log in or sign up via Email magic link</p>
                        <input type='email' name='email'/>
                        <button type='submit'>Submit</button>
                    </form>
                """.trimIndent()
                ),
                type = ContentType.Text.Html
            )
        )
    }
    val loginEmailHtmlPost = path("login-email/form-post/").post.handler {
        val email = it.body!!.text().split('&')
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
            .get("email")!!.lowercase()
        val basis = try {
            loginEmail.implementation(Unit, email)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
        HttpResponse(
            body = HttpContent.Text(
                string = HtmlDefaults.basePage(
                    """
                <p>Success!  An email has been sent with a code to log in.</p>
                <form action='../form-post-code/' enctype='application/x-www-form-urlencoded' method='post'>
                    <input type='text' name='email' value='$email'/>
                    <p>Enter Email PIN</p>
                    <input type='text' name='pin'/>
                    <button type='submit'>Submit</button>
                </form>
            """.trimIndent()
                ),
                type = ContentType.Text.Html
            )
        )
    }
    val loginEmailPinHtmlPost = path("login-email/form-post-code/").post.handler {
        val basis = try {
            val content = it.body!!.text().split('&')
                .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
            val pin = content.get("pin")!!
            val email = content.get("email")!!.lowercase()
            loginEmailPin.implementation(Unit, EmailPinLogin(email, pin))
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
        HttpResponse.redirectToGet(base.landingRoute.path.toString() + "?jwt=$basis")
    }
}

