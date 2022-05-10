package com.lightningkite.ktorbatteries.serverhealth

import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.event.Level
import java.lang.management.ManagementFactory
import java.lang.management.OperatingSystemMXBean
import java.net.NetworkInterface
import java.time.Instant
import kotlin.streams.toList

interface HealthCheckable {
    val healthCheckName: String get() = this::class.simpleName ?: "???"
    suspend fun healthCheck(): HealthStatus
}

data class HealthStatus(val level: Level, val checkedAt: Instant = Instant.now(), val additionalMessage: String? = null) {
    enum class Level(val color: String) {
        OK("green"),
        WARNING("yellow"),
        URGENT("orange"),
        ERROR("red")
    }
}

private val healthCache = HashMap<HealthCheckable, HealthStatus>()
fun Route.configureHealth(path: String, features: List<HealthCheckable>) {
    get(path) {

        val now = Instant.now()
        val results = features
            .map {
                healthCache[it]?.takeIf {
                    now.toEpochMilli() - it.checkedAt.toEpochMilli() < 60_000 && it.level <= HealthStatus.Level.WARNING
                }?.let { s -> return@map it.healthCheckName to s }
                val result = it.healthCheck()
                healthCache[it] = result
                return@map it.healthCheckName to result
            }

        val resultHtml = results
            .joinToString("") { status ->
                //language=HTML
                """
                <div>
                    <div style='font-size: 18px; font-weight: bold;'>${status.first}</div>
                     <div style='color: ${status.second.level.color};'>${status.second.level.name}</div>
                     ${status.second.additionalMessage?.let { "<div>$it</div>" } ?: ""}
                </div>
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

        val sendBadCode = loadAverage > 70 || memUsagePercent > 70 || results.any { it.second.level > HealthStatus.Level.WARNING }

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
            <p>Server ID: ${NetworkInterface.getNetworkInterfaces().toList().sortedBy { it.name }.firstOrNull()?.hardwareAddress?.sumOf { it.hashCode() }?.toString(16)}</p>
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

