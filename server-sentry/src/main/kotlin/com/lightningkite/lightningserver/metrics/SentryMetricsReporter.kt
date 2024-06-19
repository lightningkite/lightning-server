package com.lightningkite.lightningserver.metrics

import com.lightningkite.lightningserver.settings.generalSettings
import io.sentry.Hint
import io.sentry.Sentry
import io.sentry.protocol.MeasurementValue
import io.sentry.protocol.SentryTransaction
import io.sentry.protocol.TransactionInfo
import org.slf4j.LoggerFactory

/**
 * An ExceptionReporter implementation that sends all reports to an external Sentry service.
 *
 * @param dsn The connection string used to connect to the Sentry Server.
 */
class SentryMetricsReporter(
    override val settings: MetricSettings,
    val dsn: String,
) : Metrics {

    companion object {
        val logger = LoggerFactory.getLogger(SentryMetricsReporter::class.java)

        init {
            MetricSettings.register("sentry") { settings ->
                Regex("""sentry://(?<dsn>https://[^@]*@[^/]*/\d+)""")
                    .matchEntire(settings.url)
                    ?.let { matches -> SentryMetricsReporter(settings, matches.groups["dsn"]!!.value) }
                    ?: throw IllegalStateException("Invalid sentry URL. The URL should match the pattern: sentry://https://[Sentry Key]@[Host]/[Project Number]")
            }
        }
    }

    init {
        if (!Sentry.isEnabled()) {
//            val name = generalSettings().projectName.filter { it.isLetterOrDigit() }
//            val version = System.getenv("AWS_LAMBDA_FUNCTION_VERSION")?.takeUnless { it.isBlank() } ?: "UNKNOWN"
            Sentry.init { options ->
                options.dsn = dsn
                options.release = "Test Version"
                options.environment = "Test Environment"
//                options.enable
//                options.isEnableMetrics = true
            }
        }
    }


    override suspend fun report(events: List<MetricEvent>) {
        logger.debug(
            "Sending ${events.size} metric points to Sentry.  Types include ${
                events.map { it.metricType.name }.distinct().joinToString()
            }"
        )

        events.forEach { event ->
            Sentry.getCurrentHub().also { println("Have Hub") }.captureTransaction(
                SentryTransaction(
                    /* transaction = */ event.entryPoint ?: "N/A",
                    /* startTimestamp = */
                    event.time.toEpochMilliseconds().toDouble().div(1000),
                    /* timestamp = */
                    null,
                    /* spans = */
                    emptyList(),
                    /* measurements = */
                    mapOf(event.metricType.name to MeasurementValue(event.value, event.metricType.unit.name)),
                    /* metricsSummaries = */
                    emptyMap(),
                    /* transactionInfo = */
                    TransactionInfo("route")
                ).also { println("Have Transaction") },
                Hint()
            )
            Sentry.flush(5000)
            println("Submitted Transaction")
        }

        logger.debug(
            "Sent ${events.size} metric points to Sentry.  Types include ${
                events.map { it.metricType.name }.distinct().joinToString()
            }"
        )
    }
}