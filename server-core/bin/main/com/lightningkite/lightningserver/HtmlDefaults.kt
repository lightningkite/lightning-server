package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningserver.settings.generalSettings

object HtmlDefaults {
    var basePage: (content: String) -> String = { content ->
        """
            <!DOCTYPE html>
            <html>
              <head>
                <meta charset="utf-8">
                <title>${generalSettings().projectName}</title>
              </head>
              <body>
                $content
              </body>
            </html>
        """.trimIndent()
    }
    var baseEmail: (content: String) -> String = { content ->
        """
            <!DOCTYPE html>
            <html>
              <head>
                <meta charset="utf-8">
                <title>${generalSettings().projectName}</title>
              </head>
              <body>
                $content
              </body>
            </html>
        """.trimIndent()
    }
    var defaultLoginEmailTemplate: (suspend (email: String, link: String) -> String) = { email: String, link: String ->
        baseEmail("""
        <p>We received a request for a login email for ${email}. To log in, please click the link below.</p>
        <a href="$link">Click here to login</a>
        <p>If you did not request to be logged in, you can simply ignore this email.</p>
        <h3>${generalSettings().projectName}</h3>
        """.trimIndent())
    }
}