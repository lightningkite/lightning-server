package com.lightningkite.ktorbatteries.exceptions

import com.lightningkite.ktorbatteries.BadFormatException
import com.lightningkite.ktorbatteries.UUIDException
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import com.mongodb.MongoWriteException
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.util.pipeline.*
import java.io.PrintWriter
import java.io.StringWriter


class ForbiddenException:Exception()
class AuthenticationException:Exception()

//fun Application.configureExceptions() {
//    install(StatusPages){
//        on<AuthenticationException>(HttpStatusCode.Unauthorized){ call.respond() }
//        on<ForbiddenException>(HttpStatusCode.Forbidden){ call.respond() }
//        on<BadFormatException>(HttpStatusCode.BadRequest) { error -> call.respond(, error.message ?: "An Unknown Error Occurred") }
//        on<UUIDException>(HttpStatusCode.NotFound) { call.respond( ) }
//        on<MongoWriteException>(HttpStatusCode.BadRequest) { error -> call.respond(, error.message ?: "An Unknown Error Occurred") }
//        on<ContentTransformationException>(HttpStatusCode.BadRequest) { error -> call.respond(, error.message ?: "An Unknown Error Occurred") }
//        on<Throwable> { cause ->
//            call.respond(HttpStatusCode.InternalServerError, "Internal Server Error")
//            throw cause
//        }
//    }
//}
///**
// * Register exception [handler] for exception type [T] and it's children.
// * If debug mode is on, the stack trace is emitted.
// */
//public inline fun <reified T : Throwable> StatusPages.Configuration.on(
//    code: HttpStatusCode,
//    noinline release: suspend PipelineContext<Unit, ApplicationCall>.(T) -> Unit = { call.respondText(it.message ?: it::class.simpleName ?: "Unknown Error") }
//): Unit {
//    if(GeneralServerSettings.instance.debug) {
//        exception(T::class.java) {
//            val writer = StringWriter()
//            PrintWriter(writer).use { w -> it.printStackTrace(w) }
//            call.respondText(writer.toString(), ContentType.Text.Plain, code)
//        }
//    } else {
//        exception(T::class.java) {
//            this.call.response.status(code)
//            release(it)
//        }
//    }
//}
