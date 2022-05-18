package com.lightningkite.ktorbatteries.exceptions

import com.lightningkite.ktorbatteries.HtmlDefaults
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import com.mongodb.MongoWriteException
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.pipeline.*
import io.sentry.Sentry
import java.io.PrintWriter
import java.io.StringWriter


class ForbiddenException : Exception()
class AuthenticationException : Exception()

fun Application.configureExceptions() {
    install(StatusPages){
        exception<ForbiddenException> { call, it ->
            call.respondText(status = HttpStatusCode.Forbidden, contentType = ContentType.Text.Html, text = HtmlDefaults.basePage("""
                <h1>Not Allowed</h1>
                <p>Sorry, you don't have permission to perform this action.</p>
            """.trimIndent()))
        }
        exception<AuthenticationException> { call, it ->
            call.response.cookies.appendExpired(HttpHeaders.Authorization)
            call.respondText(status = HttpStatusCode.Unauthorized, contentType = ContentType.Text.Html, text = HtmlDefaults.basePage("""
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
fun ApplicationCall.reportException(throwable: Throwable) {
    if(ExceptionSettings.instance.sentryDsn != null) {
        val ctx = Sentry.getContext()
        ctx.clear()
        ctx.addExtra("path", request.path())
        ctx.addExtra("method", request.httpMethod.value)
        ctx.addExtra("queryParameters", request.queryParameters.entries().filter { it.key != "jwt" }.joinToString("\n") { it.key + ": " + it.value })
        ctx.addExtra("headers", request.headers.entries().filter {
            it.key.lowercase() !in filterHeaders
        }.joinToString("\n") { it.key + ": " + it.value })
        ctx.addExtra("user", principal<Principal>()?.toString() ?: "anonymous")
        Sentry.capture(throwable)
        ctx.clear()
    } else {
        throwable.printStackTrace()
    }
}

/**
 * Register exception [handler] for exception type [T] and it's children.
 * If debug mode is on, the stack trace is emitted.
 */
//public inline fun <reified T : Throwable> StatusPagesConfig.on(
//    code: HttpStatusCode,
//    noinline release: suspend ApplicationCall.(T) -> Unit = { call.respondText(it.message ?: it::class.simpleName ?: "Unknown Error") }
//): Unit {
//    if(GeneralServerSettings.instance.debug) {
//        exception(T::class.java) {
//            println("Handling $it")
//            val writer = StringWriter()
//            PrintWriter(writer).use { w -> it.printStackTrace(w) }
//            call.respondText(writer.toString(), ContentType.Text.Plain, code)
//        }
//    } else {
//        exception(T::class.java) {
//            println("Handling $it for release")
//            this.call.response.status(code)
//            release(it)
//        }
//    }
//}
