package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningserver.core.serverEntryPoint
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.settings.generalSettings

/**
 * Will report an Exception to the underlying reporting system.
 * HttpStatusExceptions of code 500 and any other unhandled exceptions will be reported.
 */
suspend fun Throwable.report(context: Any? = null) {
    if (generalSettings().debug) logger.debug(this.stackTraceToString())
    if (this is HttpStatusException && this.status.code / 100 != 5) return
    exceptionSettings().report(this, context ?: serverEntryPoint())
}
