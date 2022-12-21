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
import java.util.*

open class SmsAuthEndpoints2<USER : Any, ID>(
    val base: BaseAuthEndpoints<USER, ID>,
    val phoneAccess: UserPhoneAccess<USER, ID>,
    private val cache: () -> CacheInterface,
    private val sms: () -> SMSClient,
    private val template: suspend (code: String) -> String = { code -> "Your ${generalSettings().projectName} code is ${code}. Don't share this with anyone." },
) : ServerPathGroup(base.path) {
    private fun cacheKey(uuid: UUID): String = "sms_pin_login_$uuid"
    val loginSms = path("login-sms").post.typed(
        summary = "SMS Login Code",
        description = "Sends a login text to the given phone",
        errorCases = listOf(),
        successCode = HttpStatus.NoContent,
        implementation = { user: Unit, phoneUnsafe: String ->
            val phone = phoneUnsafe.filter { it.isDigit() }
            val pin = SecureRandom().nextInt(1000000).toString().padStart(6, '0')
            val secret = UUID.randomUUID()
            cache().set(cacheKey(secret), phone + "|" + pin.secureHash(), base.jwtSigner().emailExpiration)
            sms().send(
                to = phone,
                message = template(pin)
            )
            secret
        }
    )
    val loginSmsPin = path("login-sms/{secret}").post.typed(
        summary = "SMS PIN Login",
        description = "Logs in to the given phone with a PIN",
        errorCases = listOf(),
        successCode = HttpStatus.OK,
        implementation = { anon: Unit, secret: UUID, input: String ->
            val data = cache().get<String>(cacheKey(secret))
                ?: throw NotFoundException("PIN has expired.")
            val phone = data.substringBefore('|')
            val hashedPin = data.substringAfter('|')
            if (!input.checkHash(hashedPin)) throw BadRequestException("Incorrect PIN")
            cache().remove(cacheKey(secret))
            base.typedHandler.token(phoneAccess.byPhone(phone))
        }
    )

    val loginEmailHtml = path("login-sms/").get.handler {
        HttpResponse(
            body = HttpContent.Text(
                string = HtmlDefaults.basePage(
                    """
                    <form action='form-post/' enctype='application/x-www-form-urlencoded' method='post'>
                        <p>Log in or sign up via SMS PIN</p>
                        <input type='sms' name='sms'/>
                        <button type='submit'>Submit</button>
                    </form>
                """.trimIndent()
                ),
                type = ContentType.Text.Html
            )
        )
    }
    val loginEmailHtmlPost = path("login-sms/form-post/").post.handler {
        val sms = it.body!!.text().split('&')
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
            .get("sms")!!.lowercase()
        val secret = try {
            loginSms.implementation(Unit, sms)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
        HttpResponse(
            body = HttpContent.Text(
                string = HtmlDefaults.basePage(
                    """
                <p>Success!  An sms has been sent with a code to log in.</p>
                <form action='../form-post-code/$secret/' enctype='application/x-www-form-urlencoded' method='post'>
                    <p>Enter Sms PIN</p>
                    <input type='text' name='pin'/>
                    <button type='submit'>Submit</button>
                </form>
            """.trimIndent()
                ),
                type = ContentType.Text.Html
            )
        )
    }
    val loginEmailPinHtmlPost = path("login-sms/form-post-code/{secret}/").post.handler {
        val basis = try {
            val content = it.body!!.text().split('&')
                .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
            val pin = content.get("pin")!!
            val secret = UUID.fromString(it.parts["secret"]!!)
            loginSmsPin.implementation(Unit, secret, pin)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
        HttpResponse.redirectToGet(base.landingRoute.path.toString() + "?jwt=$basis")
    }
}