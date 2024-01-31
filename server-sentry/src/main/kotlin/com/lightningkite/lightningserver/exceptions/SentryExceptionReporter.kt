package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningdb.HasEmail
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.authAny
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.schedule.ScheduledTask
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.tasks.Task
import com.lightningkite.lightningserver.websocket.WebSockets
import io.ktor.http.*
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.protocol.Request
import io.sentry.protocol.User

/**
 * An ExceptionReporter implementation that sends all reports to an external Sentry service.
 *
 * @param dsn The connection string used to connect to the Sentry Server.
 */
class SentryExceptionReporter(val dsn: String) : ExceptionReporter {
    companion object {
        init {
            ExceptionSettings.register("sentry") {
                SentryExceptionReporter(it.url.substringAfter("://"))
            }
        }
    }

    init {
        val name = generalSettings().projectName.filter { it.isLetterOrDigit() }
        val version = System.getenv("AWS_LAMBDA_FUNCTION_VERSION")?.takeUnless { it.isBlank() } ?: "UNKNOWN"
        Sentry.init { options ->
            options.dsn = dsn
            options.release = version
            options.environment = name
        }
    }

    override suspend fun report(t: Throwable, context: Any?): Boolean {
        when (context) {
            is HttpRequest -> {
                val p = context.authAny()?.get()
                val event = SentryEvent(t).apply {
                    this.request
                    request = Request().apply {
                        url = context.endpoint.path.toString()
                        method = context.endpoint.method.toString()
                        queryString = context.queryParameters.joinToString("&") { "${it.first}=${it.second}" }
                        cookies = context.headers.cookies.entries.joinToString { "${it.key}=${it.value}" }
                        headers = context.headers.normalizedEntries.mapValues { it.value.joinToString { it } }

                        envs = mapOf(
                            "REMOTE_ADDR" to context.sourceIp,
                            "REQUEST_SECURE" to generalSettings().publicUrl.startsWith("https").toString(),
                        )
                    }
                    user = User().apply {
                        id = p.simpleUserId()
                        username = p.simpleUserTag()
                        ipAddress = context.sourceIp
                        email = p.simpleUserTag().takeIf { it.contains('@') }
                        data = mapOf("stringRepresentation" to p.toString())
                    }
                }
                Sentry.captureEvent(event)
            }

            is WebSockets.ConnectEvent -> {
                val p = context.authAny()?.get()
                val event = SentryEvent(t).apply {
                    request = Request().apply {
                        url = context.path.toString()
                        method = "WS"
                        queryString = context.queryParameters.joinToString("&") { "${it.first}=${it.second}" }
                        cookies = context.headers.cookies.entries.joinToString { "${it.key}=${it.value}" }
                        headers = context.headers.normalizedEntries.mapValues { it.value.joinToString { it } }

                        envs = mapOf(
                            "REMOTE_ADDR" to context.sourceIp,
                            "REQUEST_SECURE" to generalSettings().publicUrl.startsWith("https").toString(),
                        )
                    }
                    user = User().apply {
                        id = p.simpleUserId()
                        username = p.simpleUserTag()
                        ipAddress = context.sourceIp
                        email = p.simpleUserTag().takeIf { it.contains('@') }
                        data = mapOf("stringRepresentation" to p.toString())
                    }
                }
                Sentry.captureEvent(event)
            }

            is Task<*> -> {
                val event = SentryEvent(t)
                event.tags = mapOf(
                    "task" to context.name
                )
                Sentry.captureEvent(event)
            }

            is ScheduledTask -> {
                val event = SentryEvent(t)
                event.tags = mapOf(
                    "schedule" to context.name
                )
                Sentry.captureEvent(event)
            }

            else -> {
                val event = SentryEvent(t)
                event.tags = mapOf(
                    "customContext" to context.toString()
                )
                Sentry.captureEvent(event)
            }
        }
        return true
    }
}

private val filterHeaders = setOf(
    HttpHeaders.Authorization.lowercase(),
    HttpHeaders.Cookie.lowercase(),
)

private fun Any?.simpleUserTag(): String = when (this) {
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