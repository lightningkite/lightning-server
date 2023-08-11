package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.settings.Pluggable
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
                val options = it.url.substringAfter("://")
                val db = options.substringBefore('|')
                val packageName = options.substringAfter('|')
                GroupedDatabaseExceptionReporter(packageName, DatabaseSettings(db))
            }
        }
    }

    override fun invoke(): ExceptionReporter = parse(url.substringBefore("://"), this)
}

val exceptionSettings = setting("exceptions", ExceptionSettings())