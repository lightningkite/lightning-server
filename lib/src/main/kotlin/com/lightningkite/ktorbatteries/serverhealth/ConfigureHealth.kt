package com.lightningkite.ktorbatteries.serverhealth

import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.lang.management.ManagementFactory
import java.lang.management.OperatingSystemMXBean

interface HealthCheckable {
    suspend fun healthCheck(): HealthStatus
}

data class HealthStatus(val name: String, val ok: Boolean, val additionalMessage: String? = null)

fun Route.configureHealth(path: String, features: List<HealthCheckable>) {
    get(path) {

        val results = features
            .map { it.healthCheck() }

        val resultHtml = results
            .joinToString("") { status ->
                //language=HTML
                """
                <p>
                    <div style='font-size: 18px; font-weight: bold;'>${status.name}</div>
                     <div style='color: ${if (status.ok) "green" else "red"};'>${if (status.ok) "Good" else "Bad"}</div>
                     ${status.additionalMessage?.let { "<div>$it</div>" } ?: ""}
                </p>
                """.trimIndent()
            }


        System.gc()
        val maxMem = Runtime.getRuntime().maxMemory()
        val totalMemory = Runtime.getRuntime().totalMemory()
        val freeMemory = Runtime.getRuntime().freeMemory()
        val systemAllocated = totalMemory - freeMemory
        val memUsagePercent: Float = (((systemAllocated.toFloat() / maxMem.toFloat() * 100f) * 100).toInt()) / 100f

        val osBean: OperatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean()
        val loadAverage = osBean.systemLoadAverage

        val sendBadCode = loadAverage > 70 || memUsagePercent > 70 || results.any { !it.ok }

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
            <h2>Server Features</h2>
            $resultHtml
            
            <h2>System</h2>
            
            <div style='font-size: 18px; font-weight: bold;'>Memory</div>
            <div style='color: ${if (memUsagePercent > 85) "red" else if (memUsagePercent > 70) "yellow" else "green"};'>Percent: $memUsagePercent %</div>
            <br/>
            <div style='font-size: 18px; font-weight: bold;'>CPU</div>
            <div style='color: ${if (loadAverage > 85) "red" else if (loadAverage > 70) "yellow" else "green"};'>Load Average: $loadAverage %</div>
            </body>
            </html>
            """.trimIndent(),
            ContentType.Text.Html,
            if (sendBadCode) HttpStatusCode.InternalServerError else HttpStatusCode.OK
        )
    }
}

