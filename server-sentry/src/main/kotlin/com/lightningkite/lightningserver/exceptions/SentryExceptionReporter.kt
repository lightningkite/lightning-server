package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningdb.HasEmail
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.schedule.Schedule
import com.lightningkite.lightningserver.schedule.ScheduledTask
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.tasks.Task
import com.lightningkite.lightningserver.websocket.WebSockets
import io.ktor.http.*
import io.sentry.Sentry
import io.sentry.SentryOptions
import io.sentry.event.User
import io.sentry.event.interfaces.HttpInterface

/**
 * An ExceptionReporter implementation that sends all reports to an external Sentry service.
 *
 * @param dsn The connection string used to connect to the Sentry Server.
 */
class SentryExceptionReporter(val dsn: String): ExceptionReporter {
    companion object {
        init {
            ExceptionSettings.register("sentry") {
                SentryExceptionReporter(it.url.substringAfter("://"))
            }
        }
    }

    init {
        val name = generalSettings().projectName.filter { it.isLetterOrDigit() }
        val version = System.getenv("AWS_LAMBDA_FUNCTION_VERSION").takeUnless { it.isBlank() } ?: "UNKNOWN"
        Sentry.init("$dsn?release=$version&environment=$name")
    }

    override suspend fun report(t: Throwable, context: Any?): Boolean {
        val ctx = Sentry.getContext()
        when (context) {
            is HttpRequest -> {
                val p = Authentication.any(context)?.value
                ctx.clear()
                ctx.http = HttpInterface(
                    context.endpoint.path.toString(),
                    context.endpoint.method.toString(),
                    context.queryParameters.groupBy { it.first }.mapValues { it.value.map { it.second } },
                    null,
                    context.headers.cookies.filter { it.key.lowercase() !in filterHeaders },
                    context.sourceIp,
                    null,
                    -1,
                    null,
                    "null",
                    -1,
                    null,
                    generalSettings().publicUrl.startsWith("https"),
                    false,
                    "",
                    "",
                    context.headers.normalizedEntries.filter { it.key.lowercase() !in filterHeaders },
                    null
                )
                ctx.user = User(
                    p.simpleUserId(),
                    p.simpleUserTag(),
                    context.sourceIp,
                    p.simpleUserTag().takeIf { it.contains('@') },
                    mapOf("stringRepresentation" to p.toString())
                )
                Sentry.capture(t)
                ctx.clear()
            }
            is WebSockets.ConnectEvent -> {
                ctx.clear()
                val p = Authentication.any(context)?.value
                ctx.http = HttpInterface(
                    context.path.toString(),
                    "WS",
                    context.queryParameters.groupBy { it.first }.mapValues { it.value.map { it.second } },
                    null,
                    context.headers.cookies.filter { it.key.lowercase() !in filterHeaders },
                    context.sourceIp,
                    null,
                    -1,
                    null,
                    "null",
                    -1,
                    null,
                    generalSettings().publicUrl.startsWith("https"),
                    false,
                    "",
                    "",
                    context.headers.normalizedEntries.filter { it.key.lowercase() !in filterHeaders },
                    null
                )
                ctx.user = User(
                    p.simpleUserId(),
                    p.simpleUserTag(),
                    context.sourceIp,
                    p.simpleUserTag().takeIf { it.contains('@') },
                    mapOf("stringRepresentation" to p.toString())
                )
                Sentry.capture(t)
                ctx.clear()
            }
            is Task<*> -> {
                ctx.clear()
                ctx.addTag("task", context.name)
                Sentry.capture(t)
                ctx.clear()
            }
            is ScheduledTask -> {
                ctx.clear()
                ctx.addTag("schedule", context.name)
                Sentry.capture(t)
                ctx.clear()
            }
            else -> {
                ctx.clear()
                ctx.addExtra("customContext", context.toString())
                Sentry.capture(t)
                ctx.clear()
            }
        }
        return true
    }
}
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