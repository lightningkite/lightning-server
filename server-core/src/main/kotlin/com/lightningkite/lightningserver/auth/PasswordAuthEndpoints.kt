package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.HtmlDefaults
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.typed.typed
import java.net.URLDecoder

/**
 * Authentication via password.
 * Strongly not recommended.
 */
open class PasswordAuthEndpoints<USER : Any, ID>(
    val base: BaseAuthEndpoints<USER, ID>,
    val info: UserPasswordAccess<USER, ID>
) : ServerPathGroup(base.path) {
    val loginPassword = path("login-password").post.typed(
        summary = "Password Login",
        description = "Log in with a password",
        errorCases = listOf(),
        implementation = { anon: Unit, input: PasswordLogin ->
            val user = info.byUsername(input.username, input.password)
            if (!input.password.checkHash(info.hashedPassword(user)))
                throw BadRequestException(
                    detail = "password-incorrect",
                    message = "Password does not match the account."
                )
            base.token(user, base.jwtSigner().expiration)
        }
    )
    val loginPasswordHtml = path("login-password/").get.handler {
        HttpResponse(
            body = HttpContent.Text(
                string = HtmlDefaults.basePage(
                    """
                    <form action='form-post/' enctype='application/x-www-form-urlencoded' method='post'>
                        <p>Log in or sign up via password</p>
                        <input type='text' name='username'/>
                        <input type='password' name='password'/>
                        <button type='submit'>Submit</button>
                    </form>
                """.trimIndent()
                ),
                type = ContentType.Text.Html
            )
        )
    }
    val loginPasswordHtmlPost = path("login-password/form-post/").post.handler {
        val values = it.body!!.text().split('&')
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        val email = values.get("username")!!.lowercase()
        val password = values.get("password")!!
        val basis = try {
            loginPassword.implementation(Unit, PasswordLogin(email, password))
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
        base.redirectToLanding(basis)
    }

    fun hash(password: String): String = password.secureHash()
}

