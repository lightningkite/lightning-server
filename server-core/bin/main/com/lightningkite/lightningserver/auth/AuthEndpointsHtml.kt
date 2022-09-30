package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.HtmlDefaults
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.http.*
import java.net.URLDecoder


class AuthEndpointsHtml<USER: HasId<ID>, ID: Comparable<ID>>(val authEndpoints: AuthEndpoints<USER, ID>): ServerPathGroup(authEndpoints.path) {
    val loginEmailHtml = path("login-email/").get.handler {
        HttpResponse(
            body = HttpContent.Text(
                string = HtmlDefaults.basePage(
                    """
                    <form action='form-post/' enctype='application/x-www-form-urlencoded' method='post'>
                        <p>Log in or sign up via email magic link</p>
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
        val basis = try {
            val content = it.body!!.text().split('&')
                .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
                .get("email")!!
            authEndpoints.loginEmail.implementation(Unit, content)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
        HttpResponse(
            body = HttpContent.Text(
                string = HtmlDefaults.basePage(
                    """
                <p>Success!  An email has been sent with a link to log in.</p>
            """.trimIndent()
                ),
                type = ContentType.Text.Html
            )
        )
    }
}

@LightningServerDsl
fun <USER: HasId<ID>, ID: Comparable<ID>> AuthEndpoints<USER, ID>.authEndpointExtensionHtml(): AuthEndpointsHtml<USER, ID> {
    return AuthEndpointsHtml(this)
}