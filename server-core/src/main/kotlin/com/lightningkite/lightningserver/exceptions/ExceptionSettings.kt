package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningdb.Database
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.metrics.LightningServerMonitorMetrics
import com.lightningkite.lightningserver.metrics.MetricSettings
import com.lightningkite.lightningserver.settings.Pluggable
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.settings.setting
import kotlinx.serialization.Serializable

/**
 * Settings that define what ExceptionReporter to use and how to connect to it.
 * Any unhandled exceptions during a request or task will be reported to ExceptionReporter.
 */
@Serializable
data class ExceptionSettings(
    val url: String = "none",
    val sentryDsn: String? = null
) : () -> ExceptionReporter {
    companion object : Pluggable<ExceptionSettings, ExceptionReporter>() {
        init {
            ExceptionSettings.register("debug") { DebugExceptionReporter }
            ExceptionSettings.register("none") { NoExceptionReporter }
            ExceptionSettings.register("grouped-db") {

                Regex("""grouped-db://(?<dbString>[^|]+)\|(?<packageName>.+)""")
                    .matchEntire(it.url)
                    ?.let { match ->
                        val dbString = match.groups["dbString"]!!.value
                        val packageName = match.groups["packageName"]!!.value
                        val database = (Settings.requirements[dbString]?.invoke() as? Database)
                            ?: DatabaseSettings(dbString).invoke()
                        GroupedDatabaseExceptionReporter(packageName, database)
                    }
                    ?: throw IllegalStateException("Invalid grouped-db URL. The URL should match the pattern: grouped-db://[dbString:Database Setting Name]|[packageName]")
            }
            ExceptionSettings.register("lightningservermonitor") {
                val after = it.url.substringAfter("://")
                val domain = after.substringBefore('/')
                val token = after.substringAfter('/')
                LightningServerMonitorExceptionReporter("https://$domain", token = token)
            }
        }
    }

    override fun invoke(): ExceptionReporter = parse(url.substringBefore("://"), this)
}

val exceptionSettings = setting("exceptions", ExceptionSettings())