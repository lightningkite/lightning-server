package com.lightningkite.ktorbatteries

import com.lightningkite.ktorbatteries.settings.GeneralServerSettings

object HtmlDefaults {
    var basePage: (content: String) -> String = { content ->
        """
            <!DOCTYPE html>
            <html>
              <head>
                <meta charset="utf-8">
                <title>${GeneralServerSettings.instance.projectName}</title>
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
                <title>${GeneralServerSettings.instance.projectName}</title>
              </head>
              <body>
                $content
              </body>
            </html>
        """.trimIndent()
    }
}