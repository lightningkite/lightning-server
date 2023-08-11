package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.HtmlDefaults
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.email.Email
import com.lightningkite.lightningserver.email.EmailClient
import com.lightningkite.lightningserver.email.EmailLabeledValue
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.typed.ApiEndpoint0
import com.lightningkite.lightningserver.typed.ApiExample
import com.lightningkite.lightningserver.typed.typed
import java.net.URLDecoder
import java.time.Duration
import java.util.*

/**
 * Endpoints for authenticating via email magic link / sent PINs.
 * Also allows for OAuth based login, as most OAuth systems share email as a common identifier.
 * For information on setting up OAuth, see the respective classes, [OauthAppleEndpoints], [OauthGitHubEndpoints], [OauthGoogleEndpoints], [OauthMicrosoftEndpoints].
 */
open class EmailAuthEndpoints<USER : Any, ID>(
    val base: BaseAuthEndpoints<USER, ID>,
    val emailAccess: UserEmailAccess<USER, ID>,
    private val cache: () -> Cache,
    private val email: () -> EmailClient,
    val pinAvailableCharacters: List<Char> = ('0'..'9').toList(),
    val pinLength: Int = 6,
    val pinExpiration: Duration = Duration.ofMinutes(15),
    val pinMaxAttempts: Int = 5,
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
    val pin = PinHandler(
        cache,
        "email",
        availableCharacters = pinAvailableCharacters,
        length = pinLength,
        expiration = pinExpiration,
        maxAttempts = pinMaxAttempts,
    )
    val loginEmail = path("login-email").post.typed(
        summary = "Email Login Link",
        description = "Sends a login email to the given address.  The email will contain both a link to instantly log in and a PIN that can be entered to log in.",
        errorCases = listOf(),
        examples = listOf(
            ApiExample(
                input = "test@test.com",
                output = Unit,
            ),
            ApiExample(
                input = "TeSt@tEsT.CoM ",
                output = Unit,
                name = "Casing doesn't matter",
                notes = "The casing of the email address is ignored, and the input is trimmed."
            ),
        ),
        successCode = HttpStatus.NoContent,
        implementation = { user: Unit, addressUnsafe: String ->
            val address = addressUnsafe.lowercase().trim()
            val jwt = base.token(emailAccess.byEmail(address), base.jwtSigner().emailExpiration)
            val pin = pin.establish(address)
            val link = "${generalSettings().publicUrl}${base.landingRoute.path}?jwt=$jwt"
            email().send(
                Email(
                    subject = emailSubject(),
                    to = listOf(EmailLabeledValue(address)),
                    plainText = "Log in to ${generalSettings().projectName} as ${address}:\n$link\nPIN: $pin",
                    html = template(address, link, pin)
                )
            )
            Unit
        }
    )
    val loginEmailPin = path("login-email-pin").post.typed(
        summary = "Email PIN Login",
        description = "Logs in to the given account with a PIN that was provided in an email sent earlier.  Note that the PIN expires in ${pinExpiration.toMinutes()} minutes, and you are only permitted ${pinMaxAttempts} attempts.",
        errorCases = listOf(),
        examples = listOf(ApiExample(input = EmailPinLogin("test@test.com", pin.generate()), output = "jwt.jwt.jwt")),
        successCode = HttpStatus.OK,
        implementation = { anon: Unit, input: EmailPinLogin ->
            val email = input.email.lowercase().trim()
            pin.assert(email, input.pin)
            base.token(emailAccess.byEmail(email))
        }
    )

    val oauthSettings = OauthProviderInfo.all.map {
        it.settings.defineOptional("oauth_${it.identifierName}")
    }

    data class OauthEndpointSet(
        val loginRedirect: HttpEndpoint,
        val loginApi: ApiEndpoint0<Unit, Unit, String>,
        val callback: OauthCallbackEndpoint<UUID>,
    )

    val oauthEndpointPairs by lazy {
        OauthProviderInfo.all.zip(oauthSettings).mapNotNull {
            val rawCreds = it.second() ?: return@mapNotNull null

            @Suppress("UNCHECKED_CAST")
            val credRead = (it.first.settings as OauthProviderInfo.SettingInfo<Any>).read
            val callback = path("oauth/${it.first.pathName}/callback").oauthCallback<UUID>(
                oauthProviderInfo = it.first,
                credentials = { credRead(rawCreds) }
            ) { response, uuid ->
                val profile = it.first.getProfile(response)
                val user = emailAccess.asExternal().byExternalService(profile)
                val token = base.token(user, Duration.ofMinutes(1))
                HttpResponse.redirectToGet("${generalSettings().publicUrl}${base.landingRoute.path}?jwt=$token")
            }
            val loginRedirect = path("oauth/${it.first}/login").get.handler {
                HttpResponse.redirectToGet(callback.loginUrl(UUID.randomUUID()))
            }
            val loginApi = path("oauth/${it.first}/login").get.typed(
                summary = "Log In via ${it.first.niceName}",
                description = "Returns a URL which, when opened in a browser, will allow you to log into the system with ${it.first.niceName}.",
                errorCases = listOf(),
                examples = listOf(
                    ApiExample(
                        Unit,
                        "${it.first.loginUrl}?someparams=x"
                    )
                ),
                implementation = { anon: Unit, _: Unit ->
                    callback.loginUrl(UUID.randomUUID())
                }
            )
            OauthEndpointSet(
                loginRedirect = loginRedirect,
                loginApi = loginApi,
                callback = callback
            )
        }
    }

    init {
        Tasks.onSettingsReady {
            oauthEndpointPairs
        }
    }

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
            .get("email")!!.lowercase().trim()
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
        val basis: String = try {
            val content = it.body!!.text().split('&')
                .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
            val pin = content.get("pin")!!.trim()
            val email = content.get("email")!!.lowercase().trim()
            loginEmailPin.implementation(Unit, EmailPinLogin(email, pin))
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
        base.redirectToLanding(basis)
    }
}

