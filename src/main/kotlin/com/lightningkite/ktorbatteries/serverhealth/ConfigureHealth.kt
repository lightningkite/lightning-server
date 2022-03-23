package com.lightningkite.ktorbatteries.serverhealth

import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import io.ktor.application.*
import io.ktor.html.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*

interface HealthCheckable {
    suspend fun healthCheck(): HealthStatus
}

data class HealthStatus(val name: String, val ok: Boolean, val additionalMessage: String? = null)

fun Route.configureHealth(path: String, features: List<HealthCheckable>) {
    get(path) {

        val results = features
            .map { it.healthCheck() }
            .joinToString("") { status ->
                //language=HTML
                """
                <p>
                    <div style='font-size: 22px; font-weight: bold;'>${status.name}</div>
                     <div style='color: ${if (status.ok) "green" else "red"};'>${if (status.ok) "Good" else "Bad"}</div>
                     ${status.additionalMessage?.let { "<div>$it</div>" } ?: ""}
                </p>
                """.trimIndent()
            }

        call.respondText(
            //language=HTML
            """
            <html>
            <head>
            ${
                try {
                    //language=HTML
                    "<title>${GeneralServerSettings.instance.projectName} Health</title>\n"
                } catch (e: IllegalAccessError) {
                    //language=HTML
                    "<title>Server Health</title>\n"
                }
            }
            </head>
            <body>
            Server Health Check
            $results
            </body>
            </html>
            """.trimIndent(),
            ContentType.Text.Html,
        )
    }
}

