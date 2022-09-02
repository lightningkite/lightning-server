package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningserver.settings.Pluggable
import com.lightningkite.lightningserver.settings.setting
import kotlinx.serialization.Serializable

/**
 * ExceptionSettings is used to configure reporting unhandled exceptions to a Sentry server.
 */
@Serializable
data class ExceptionSettings(
    val url: String = "none",
    val sentryDsn: String? = null
) : ()-> ExceptionReporter {
    companion object: Pluggable<ExceptionSettings, ExceptionReporter>() {
        init {
            ExceptionSettings.register("debug") { DebugExceptionReporter }
            ExceptionSettings.register("none") { NoExceptionReporter }
        }
    }

    override fun invoke(): ExceptionReporter = parse(url.substringBefore("://"), this)
}

val exceptionSettings = setting("exceptions", ExceptionSettings())