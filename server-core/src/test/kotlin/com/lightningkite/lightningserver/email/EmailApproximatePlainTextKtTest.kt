package com.lightningkite.lightningserver.email

import com.lightningkite.lightningserver.HtmlDefaults
import com.lightningkite.lightningserver.settings.generalSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EmailApproximatePlainTextKtTest {
    @Test
    fun test(): Unit = runBlocking {
        println(HtmlDefaults.baseEmail("""
            <table role="presentation" style="width:100%;border-collapse:collapse;border:0;border-spacing:0;">
            ${
            HtmlDefaults.logo?.let {
                """
                    <tr><td align="center" style="padding:16px;"><img src="$it" alt="PROJECT"/></td></tr>
                """.trimIndent()
            } ?: ""
        }
            <tr><td align="center" style="padding:0px;"><h1>Log In to PROJECT</h1></td></tr>
            <tr><td align="center" style="padding:0px;"><p>We received a request for a login email for email@email.com. To log in, please click the link below or enter the PIN.</p></td></tr>
            <tr><td align="center" style="padding:0px;">
                <table>
                    <tr><td align="center" style="padding:16px;background-color: ${HtmlDefaults.primaryColor};border-radius: 8px"><a style="color:white;text-decoration: none;font-size: 22px;" href="https://local.com">Click here to login</a></td></tr>
                </table>
            </td></tr>
            <tr><td align="center" style="padding:0px;"><h2>PIN: 123456</h2></td></tr>
            <tr><td align="center" style="padding:0px;"><p>If you did not request to be logged in, you can simply ignore this email.</p></td></tr>
            <tr><td align="center" style="padding:0px;"><h3>PROJECT</h3></td></tr>
        </table>
        """.trimIndent()).emailApproximatePlainText())
    }
}