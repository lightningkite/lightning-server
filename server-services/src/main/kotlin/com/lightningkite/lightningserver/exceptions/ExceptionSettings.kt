package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningdb.Database
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.email.EmailClient
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningserver.services.Pluggable
import kotlinx.serialization.Serializable

/**
 * Settings that define what ExceptionReporter to use and how to connect to it.
 * Any unhandled exceptions during a request or task will be reported to ExceptionReporter.
 */
@Serializable
data class ExceptionSettings(
    val url: String = "none",
    val sentryDsn: String? = null
) : ExceptionReporter {
    companion object : Pluggable<ExceptionSettings, ExceptionReporter>() {
        init {
            ExceptionSettings.register("none") { NoExceptionReporter }
            ExceptionSettings.register("grouped-db") {

                Regex("""grouped-db://(?<dbString>[^|]+)\|(?<packageName>.+)""")
                    .matchEntire(it.url)
                    ?.let { match ->
                        val dbString = match.groups["dbString"]!!.value
                        val packageName = match.groups["packageName"]!!.value
                        val database = DatabaseSettings(dbString)
                        GroupedDatabaseExceptionReporter(packageName, database)
                    }
                    ?: throw IllegalStateException("Invalid grouped-db URL. The URL should match the pattern: grouped-db://[dbString:Database Setting Name]|[packageName]")
            }
        }
    }

    private var backing: ExceptionReporter? = null
    val wraps: ExceptionReporter
        get() {
            if(backing == null) backing = parse(url.substringBefore("://"), this)
            return backing!!
        }

    override suspend fun healthCheck(): HealthStatus = wraps.healthCheck()
    override suspend fun report(t: Throwable, context: Any?): Boolean = wraps.report(t, context)
}

