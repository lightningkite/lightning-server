package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningserver.settings.generalSettings

object HtmlDefaults {
    var logo: String? = null
    var primaryColor: String = "red"
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
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <meta name="x-apple-disable-message-reformatting">
                <title></title>
                <!--[if mso]>
                <noscript>
                    <xml>
                        <o:OfficeDocumentSettings>
                            <o:PixelsPerInch>96</o:PixelsPerInch>
                        </o:OfficeDocumentSettings>
                    </xml>
                </noscript>
                <![endif]-->
                <style>
                    h1, h2, h3, h4, h5, h6, p { font-family: sans-serif }
                    table, td {border:0px solid #000000 !important;}
                </style>
              </head>
              <body>
                $content
              </body>
            </html>
        """.trimIndent()
    }
    var defaultLoginEmailTemplate: (suspend (email: String, link: String) -> String) = { email: String, link: String ->
        baseEmail("""
        <table role="presentation" style="width:100%;border-collapse:collapse;border:0;border-spacing:0;">
            ${logo?.let {
                """
                    <tr><td align="center" style="padding:16px;"><img src="$it" alt="Lazy One"/></td></tr>
                """.trimIndent()
        } ?: ""}
            <tr><td align="center" style="padding:0px;"><h1>Log In to ${generalSettings().projectName}</h1></td></tr>
            <tr><td align="center" style="padding:0px;"><p>We received a request for a login email for ${email}. To log in, please click the link below.</p></td></tr>
            <tr><td align="center" style="padding:0px;">
                <table>
                    <tr><td align="center" style="padding:16px;background-color: $primaryColor;border-radius: 8px"><a style="color:white;text-decoration: none;font-size: 22px;" href="$link">Click here to login</a></td></tr>
                </table>
            </td></tr>
            <tr><td align="center" style="padding:0px;"><p>If you did not request to be logged in, you can simply ignore this email.</p></td></tr>
            <tr><td align="center" style="padding:0px;"><h3>${generalSettings().projectName}</h3></td></tr>
        </table>
        """.trimIndent())
    }
}