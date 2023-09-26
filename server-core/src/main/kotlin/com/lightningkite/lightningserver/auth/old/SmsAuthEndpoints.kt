package com.lightningkite.lightningserver.auth.old

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.HtmlDefaults
import com.lightningkite.lightningserver.auth.PhonePinLogin
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.sms.SMSClient
import com.lightningkite.lightningserver.typed.ApiExample
import com.lightningkite.lightningserver.typed.AuthAndPathParts
import com.lightningkite.lightningserver.typed.typed
import java.net.URLDecoder
import java.time.Duration

/**
 * Authentication endpoints for logging in with SMS PINs.
 */
open class SmsAuthEndpoints<USER : HasId<ID>, ID: Comparable<ID>>(
    val base: BaseAuthEndpoints<USER, ID>,
    val phoneAccess: UserPhoneAccess<USER, ID>,
    private val cache: () -> Cache,
    private val sms: () -> SMSClient,
    val pinAvailableCharacters: List<Char> = ('0'..'9').toList(),
    val pinLength: Int = 6,
    val pinExpiration: Duration = Duration.ofMinutes(15),
    val pinMaxAttempts: Int = 5,
    private val template: suspend (code: String) -> String = { code -> "Your ${generalSettings().projectName} code is ${code}. Don't share this with anyone." }
) : ServerPathGroup(base.path) {
    val pin = PinHandler(
        cache,
        "sms",
        availableCharacters = pinAvailableCharacters,
        length = pinLength,
        expiration = pinExpiration,
        maxAttempts = pinMaxAttempts,
    )
    val loginSms = path("login-sms").post.typed(
        summary = "SMS Login Code",
        description = "Sends a text to the given phone with a PIN that can be used to log in.",
        errorCases = listOf(),
        examples = listOf(
            ApiExample(
                input = "801-369-3729",
                output = Unit
            ),
            ApiExample(
                input = "+18013693729",
                output = Unit,
                notes = "The phone number format doesn't matter - all non-numeric characters are stripped."
            ),
        ),
        successCode = HttpStatus.NoContent,
        implementation = { user: Unit, phoneUnsafe: String ->
            val phone = phoneUnsafe.filter { it.isDigit() }
            if(phone.isEmpty()) throw BadRequestException("Blank phone number given.")
            if(phone.isEmpty()) throw BadRequestException("Invalid phone number.")
            val pin = pin.establish(phone)
            sms().send(
                to = phone,
                message = template(pin)
            )
            Unit
        }
    )
    val loginSmsPin = path("login-sms-pin").post.typed(
        summary = "SMS PIN Login",
        description = "Logs in to the given account with a PIN that was provided in a text sent earlier.  Note that the PIN expires in ${pinExpiration.toMinutes()} minutes, and you are only permitted ${pinMaxAttempts} attempts.",
        errorCases = listOf(),
        examples = listOf(ApiExample(PhonePinLogin("801-369-3729", pin.generate()), "jwt.jwt.jwt")),
        successCode = HttpStatus.OK,
        implementation = { anon: Unit, input: PhonePinLogin ->
            val phone = input.phone.filter { it.isDigit() }
            pin.assert(phone, input.pin)
            base.token(phoneAccess.byPhone(input.phone))
        }
    )

    val loginSmsHtml = path("login-sms/").get.handler {
        HttpResponse(
            body = HttpContent.Text(
                string = HtmlDefaults.basePage(
                    """
                    <form action='form-post/' enctype='application/x-www-form-urlencoded' method='post'>
                        <p>Log in or sign up via SMS magic link</p>
                        <input type='text' name='phone'/>
                        <button type='submit'>Submit</button>
                    </form>
                """.trimIndent()
                ),
                type = ContentType.Text.Html
            )
        )
    }
    val loginSmsHtmlPost = path("login-sms/form-post/").post.handler {
        val phone = it.body!!.text().split('&')
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
            .get("phone")!!.filter { it.isDigit() }
        val basis = try {
            loginSms.implementation(AuthAndPathParts(null, null, arrayOf()), phone)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
        HttpResponse(
            body = HttpContent.Text(
                string = HtmlDefaults.basePage(
                    """
                <p>Success!  A text has been sent with a code to log in.</p>
                <form action='../form-post-code/' enctype='application/x-www-form-urlencoded' method='post'>
                    <input type='text' name='phone' value='$phone'/>
                    <p>Enter SMS PIN</p>
                    <input type='text' name='pin'/>
                    <button type='submit'>Submit</button>
                </form>
            """.trimIndent()
                ),
                type = ContentType.Text.Html
            )
        )
    }
    val loginSmsPinHtmlPost = path("login-sms/form-post-code/").post.handler {
        val basis = try {
            val content = it.body!!.text().split('&')
                .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
            val pin = content.get("pin")!!
            val phone = content.get("phone")!!.filter { it.isDigit() }
            loginSmsPin.implementation(AuthAndPathParts(null, null, arrayOf()), PhonePinLogin(phone, pin))
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
        base.redirectToLanding(basis)
    }
}