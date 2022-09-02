package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningserver.HtmlDefaults
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningdb.HasEmail
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.rawUser
import com.lightningkite.lightningserver.auth.user
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.settings.generalSettings
import io.ktor.http.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Will report an Exception to Sentry if the ExceptionSettings.sentryDsn is provided
 */
suspend fun Throwable.report(context: Any? = null) {
    if(generalSettings().debug) this.printStackTrace()
    if(this is HttpStatusException && this.status.code / 100 != 5) return
    exceptionSettings().report(this, context)
}
