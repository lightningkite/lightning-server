package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningserver.HtmlDefaults
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningdb.HasEmail
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.rawUser
import com.lightningkite.lightningserver.auth.user
import com.lightningkite.lightningserver.http.HttpRequest
import com.mongodb.MongoWriteException
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import io.sentry.Sentry
import io.sentry.event.User
import io.sentry.event.interfaces.HttpInterface
import java.io.PrintWriter
import java.io.StringWriter

private val filterHeaders = setOf(
    HttpHeaders.Authorization.lowercase(),
    HttpHeaders.Cookie.lowercase(),
)

private fun Any?.simpleUserTag(): String = when(this) {
    null -> "anonymous"
    is HasEmail -> this.email
    is HasId<*> -> this._id.toString()
    else -> toString()
}

private fun Any?.simpleUserId(): String = when (this) {
    null -> "anonymous"
    is HasId<*> -> this._id.toString()
    else -> toString()
}

/**
 * Will report an Exception to Sentry if the ExceptionSettings.sentryDsn is provided
 */
suspend fun HttpRequest.reportException(throwable: Throwable) {
    if(GeneralServerSettings.instance.debug) throwable.printStackTrace()
    if (ExceptionSettings.instance.sentryDsn != null) {
        val ctx = Sentry.getContext()
        val p = this.rawUser()
        ctx.clear()
        ctx.http = HttpInterface(
            this.route.path.toString(),
            this.route.method.toString(),
            this.queryParameters.groupBy { it.first }.mapValues { it.value.map { it.second } },
            null,
            this.headers.cookies.filter { it.key.lowercase() !in filterHeaders },
            this.sourceIp,
            null,
            -1,
            null,
            "null",
            -1,
            null,
            GeneralServerSettings.instance.publicUrl.startsWith("https"),
            false,
            "",
            "",
            this.headers.normalizedEntries.filter { it.key.lowercase() !in filterHeaders },
            null
        )
        ctx.user = User(
            p.simpleUserId(),
            p.simpleUserTag(),
            this.sourceIp,
            p.simpleUserTag().takeIf { it.contains('@') },
            mapOf("stringRepresentation" to p.toString())
        )
        Sentry.capture(throwable)
        ctx.clear()
    } else {
        throwable.printStackTrace()
    }
}
