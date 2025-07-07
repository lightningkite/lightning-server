package com.lightningkite.lightningserver.exceptions

import com.lightningkite.UUID
import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.Index
import com.lightningkite.lightningdb.MaxLength
import com.lightningkite.lightningdb.References
import com.lightningkite.lightningdb.path
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.metrics.LightningServerMonitorMetrics.ApplicationMetricSpanStatsReport
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.schedule.ScheduledTask
import com.lightningkite.lightningserver.serverLogger
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.statusFailing
import com.lightningkite.lightningserver.tasks.Task
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.typed.api
import com.lightningkite.lightningserver.websocket.WebSocketConnectRequest
import com.lightningkite.now
import io.ktor.client.request.accept
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.discardRemaining
import io.ktor.http.contentType
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * An ExceptionReporter implementation that logs all reports out to a logger using debug and stores the most recent 100 exceptions.
 * An endpoint is added for retrieving the recent exceptions.
 * This is useful in a local development environment.
 */
class LightningServerMonitorExceptionReporter(
    val monitoringServerUrl: String,
    val applicationOverride: String? = null,
    val token: String
): ExceptionReporter {
    val logger = LoggerFactory.getLogger(this::class.java)
    @GenerateDataClassPaths
    @Serializable
    data class AppReport<T>(
        val application: String,
        val reportToken: String,
        val items: List<T>
    )
    @Serializable
    @GenerateDataClassPaths
    data class ApplicationStackTrace(
        override val _id: UUID = UUID.random(),
        @MaxLength(128, 32) val application: String,
        val frontend: String? = null,
        val userAgent: String? = null,
        val context: String? = null,
        val auth: String? = null,
        val version: String,
        val trace: String,
        @Index val traceHash: Int = trace.hashCode(),
        val occurrences: Int = 1,
        val first: Instant = now(),
        val last: Instant = now(),
    ) : HasId<UUID>

    val toReport = ConcurrentLinkedQueue<ApplicationStackTrace>()
    val application by lazy {
        applicationOverride ?: generalSettings().publicUrl.substringAfter("://").substringBefore('/')
    }
    var reportingOk: Boolean? = null
    init {
        Tasks.onEngineReady {
            com.lightningkite.lightningserver.engine.engine.backgroundReportingAction {
                logger.debug("Assembling exceptions to report...")
                val assembledData = ArrayList<ApplicationStackTrace>(toReport.size)
                while (true) {
                    val item = toReport.poll() ?: break
                    assembledData.add(item)
                }
                if(assembledData.isEmpty()) return@backgroundReportingAction
                val collapsed = assembledData
                    .groupBy { it.traceHash }
                    .values
                    .map { it.maxBy { it.last }.copy(occurrences = it.size) }
                try {
                    logger.debug("Reporting ${assembledData.size} (${collapsed.size} collapsed) exceptions to ${monitoringServerUrl}...")
                    client.post("$monitoringServerUrl/applicationStackTrace/accept") {
                        contentType(io.ktor.http.ContentType.Application.Json)
                        accept(io.ktor.http.ContentType.Application.Json)
                        setBody(AppReport(application, token, collapsed))
                    }.statusFailing().discardRemaining()
                    reportingOk = true
                } catch(e: Exception) {
                    reportingOk = false
                }
                logger.debug("Report complete.")
            }
        }
    }

    override suspend fun report(t: Throwable, context: Any?): Boolean {
        val contextString = when (context) {
            is HttpRequest -> context.path.toString()
            is WebSocketConnectRequest -> context.path.toString()
            is Task<*> -> context.name
            is ScheduledTask -> context.name
            else -> context.toString()
        }
        toReport.add(ApplicationStackTrace(
            application = application,
            frontend = null,
            userAgent = null,
            context = contextString,
            version = "-",
            trace = t.stackTrace.joinToString("\n"),
        ))
        return reportingOk ?: true
    }

    override suspend fun healthCheck(): HealthStatus {
        return try {
            client.post("$monitoringServerUrl/applicationStackTrace/accept") {
                contentType(io.ktor.http.ContentType.Application.Json)
                accept(io.ktor.http.ContentType.Application.Json)
                setBody(AppReport(application, token, listOf<ApplicationStackTrace>()))
            }.statusFailing().discardRemaining()
            HealthStatus(HealthStatus.Level.OK)
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }
}
