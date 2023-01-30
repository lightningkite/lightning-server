package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.HtmlDefaults
import com.lightningkite.lightningserver.cache.CacheInterface
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.set
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.sms.SMSClient
import com.lightningkite.lightningserver.typed.typed
import java.net.URLDecoder
import java.security.SecureRandom
import java.time.Duration

open class SmsAuthEndpoints<USER : Any, ID>(
    val base: BaseAuthEndpoints<USER, ID>,
    val phoneAccess: UserPhoneAccess<USER, ID>,
    private val cache: () -> CacheInterface,
    private val sms: () -> SMSClient,
    private val template: suspend (code: String) -> String = { code -> "Your ${generalSettings().projectName} code is ${code}. Don't share this with anyone." }
) : ServerPathGroup(base.path) {
    val pin = PinHandler(cache, "sms")
    val loginSms = path("login-sms").post.typed(
        summary = "SMS Login Code",
        description = "Sends a login text to the given phone",
        errorCases = listOf(),
        successCode = HttpStatus.NoContent,
        implementation = { user: Unit, phoneUnsafe: String ->
            val phone = phoneUnsafe.filter { it.isDigit() }
            val pin = pin.generate(phone)
            sms().send(
                to = phone,
                message = template(pin)
            )
            Unit
        }
    )
    val loginSmsPin = path("login-sms-pin").post.typed(
        summary = "SMS PIN Login",
        description = "Logs in to the given phone with a PIN",
        errorCases = listOf(),
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
            loginSms.implementation(Unit, phone)
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
            loginSmsPin.implementation(Unit, PhonePinLogin(phone, pin))
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
        HttpResponse.redirectToGet(base.landingRoute.path.toString() + "?jwt=$basis")
    }
}