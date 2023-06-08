package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.settings.generalSettings


/**
 * HtmlDefaults Is a place to hold html templates. You can set certain styling and headers here as a template, then call it with content
 * all throughout the server.
 */
object HtmlDefaults {
    /**
     * The logo of the company as an HTML element string.
     */
    var logo: String? by SetOnce { null }
    /**
     * The primary color of the company as a CSS color.
     */
    var primaryColor: String by SetOnce { "red" }

    /**
     * Default HTML wrapper for an HTML page in the system
     */
    var basePage: (content: String) -> String by SetOnce {
        { content ->
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
    }

    /**
     * Default HTML wrapper for an email from the system
     */
    var baseEmail: (content: String) -> String by SetOnce {
        { content ->
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
    }
}