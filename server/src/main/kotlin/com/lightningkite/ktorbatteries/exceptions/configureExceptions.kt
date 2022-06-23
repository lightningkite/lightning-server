package com.lightningkite.ktorbatteries.exceptions

import com.lightningkite.ktorbatteries.HtmlDefaults
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import com.lightningkite.ktorbatteries.typed.BoxPrincipal
import com.lightningkite.ktordb.HasEmail
import com.lightningkite.ktordb.HasId
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


class ForbiddenException : Exception()
class AuthenticationException : Exception()

/**
 * Configures the StatusPages plugin. It automatically handles common exceptions such as forbidden, not found, and bad request.
 * If ExceptionSettings is set with a sentryDsn then any unspecified exceptions will be reported to Sentry.
 * customExceptions allows you to define your own custom exception handling.
 */
fun Application.configureExceptions(customExceptions: (StatusPagesConfig.() -> Unit)? = null) {
    install(StatusPages) {
        exception<ForbiddenException> { call, it ->
            call.respondText(
                status = HttpStatusCode.Forbidden, contentType = ContentType.Text.Html, text = HtmlDefaults.basePage(
                    """
                <h1>Not Allowed</h1>
                <p>Sorry, you don't have permission to perform this action.</p>
            """.trimIndent()
                )
            )
        }
        exception<AuthenticationException> { call, it ->
            call.response.cookies.appendExpired(HttpHeaders.Authorization)
            call.respondText(
                status = HttpStatusCode.Unauthorized, contentType = ContentType.Text.Html, text = HtmlDefaults.basePage(
                    """
                <h1>Log-in Issue</h1>
                <p>Something is wrong with your log-in.  Please log in again.</p>
            """.trimIndent()))
        }
        exception<BadRequestException> { call, it ->
            call.respondText(status = HttpStatusCode.BadRequest, contentType = ContentType.Text.Html, text = HtmlDefaults.basePage("""
                <h1>Hmm...</h1>
                <p>Something about your request was incorrect.</p>
                <p>${it.message}</p>
            """.trimIndent()))
        }
        exception<NotFoundException> { call, it ->
            call.respondText(status = HttpStatusCode.NotFound, contentType = ContentType.Text.Html, text = HtmlDefaults.basePage("""
                <h1>Not Found</h1>
                <p>We can't find what you're looking for.</p>
            """.trimIndent()))
        }
        exception<UnsupportedMediaTypeException> { call, it ->
            call.respondText(status = HttpStatusCode.UnsupportedMediaType, contentType = ContentType.Text.Html, text = HtmlDefaults.basePage("""
                <h1>Unsupported Media Type</h1>
                <p>We can't read or write in that format.  Sorry!</p>
            """.trimIndent()))
        }
        customExceptions?.invoke(this)
        exception<Exception> { call, it ->
            call.respondText(status = HttpStatusCode.InternalServerError, contentType = ContentType.Text.Html, text = HtmlDefaults.basePage("""
                <h1>Oh no!</h1>
                <p>Something went wrong.  We're terribly sorry.  If this continues, see if you can contact the developer.</p>
            """.trimIndent()))
            call.reportException(it)
        }
    }
}

private val filterHeaders = setOf(
    HttpHeaders.Authorization.lowercase(),
    HttpHeaders.Cookie.lowercase(),
)
private fun Any?.simpleUserTag(): String = when(this) {
    null -> "anonymous"
    is BoxPrincipal<*> -> this.user.simpleUserTag()
    is HasEmail -> this.email
    is HasId<*> -> this._id.toString()
    else -> toString()
}

private fun Any?.simpleUserId(): String = when (this) {
    null -> "anonymous"
    is BoxPrincipal<*> -> this.user.simpleUserId()
    is HasId<*> -> this._id.toString()
    else -> toString()
}

/**
 * Will report an Exception to Sentry if the ExceptionSettings.sentryDsn is provided
 */
fun ApplicationCall.reportException(throwable: Throwable) {
    if (ExceptionSettings.instance.sentryDsn != null) {
        val ctx = Sentry.getContext()
        val p = principal<Principal>()
        ctx.clear()
        ctx.http = HttpInterface(
            request.uri,
            request.httpMethod.value,
            request.queryParameters.toMap().filter { it.key != "jwt" },
            null,
            request.cookies.rawCookies.filter { it.key.lowercase() !in filterHeaders },
            request.origin.remoteHost,
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
            request.headers.toMap().filter { it.key.lowercase() !in filterHeaders },
            null
        )
        ctx.user = User(
            p.simpleUserId(),
            p.simpleUserTag(),
            (request.headers.get("X-Forwarded-For") ?: request.origin.remoteHost).takeIf { it.all { it.isDigit() || it == '.' } },
            p.simpleUserTag().takeIf { it.contains('@') },
            mapOf("stringRepresentation" to p.toString())
        )
        Sentry.capture(throwable)
        ctx.clear()
    } else {
        throwable.printStackTrace()
    }
}
