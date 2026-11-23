package com.lightningkite.lightningserver.guide.samples

// region error-handling-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.data.*
import kotlinx.serialization.*
// endregion error-handling-imports

// region error-server
@Serializable
data class SignupForm(val email: String)

object ErrorsServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    private val takenEmails = setOf("taken@example.com")

    // POST /signup
    val signup = path.path("signup").post bind ApiHttpHandler(
        summary = "Sign up",
        auth = noAuth,
        successCode = HttpStatus.Created,
        errorCases = listOf(
            LSError(http = 400, detail = "invalid-email", message = "That is not an email address"),
            LSError(http = 409, detail = "email-taken", message = "That email address is already in use"),
        ),
        implementation = { input: SignupForm ->
            if ("@" !in input.email)
                throw BadRequestException(detail = "invalid-email", message = "That is not an email address")
            if (input.email in takenEmails)
                throw HttpStatusException(
                    status = HttpStatus.Conflict,
                    detail = "email-taken",
                    message = "That email address is already in use",
                    data = input.email,
                )
            "Welcome!"
        }
    )

    // GET /busy — an error response with extra headers
    val busy = path.path("busy").get bind HttpHandler {
        throw HttpStatusException(
            status = HttpStatus.ServiceUnavailable,
            detail = "maintenance",
            message = "Down for maintenance",
            headers = HttpHeaders(HttpHeader.RetryAfter to "120"),
        )
    }
}
// endregion error-server

// region error-typed-test
fun errorTypedTest() = ErrorsServer.testBlocking(settings = {}) {
    try {
        ErrorsServer.signup.test(null, SignupForm("taken@example.com"))
        error("Expected a conflict")
    } catch (e: HttpStatusException) {
        check(e.status == HttpStatus.Conflict)
        check(e.detail == "email-taken")
    }
}
// endregion error-typed-test

// region error-http-test
fun errorHttpTest() = ErrorsServer.testBlocking(settings = {}) {
    val response = ErrorsServer.signup.test(
        body = TypedData.text("""{"email":"not-an-email"}""", MediaType.Application.Json)
    )
    check(response.status == HttpStatus.BadRequest)
    // Decoding the body needs a ServerRuntime, so it runs inside execute { }.
    val error = execute { response.toLSError() }
    check(error.detail == "invalid-email")

    val busy = ErrorsServer.busy.test()
    check(busy.status == HttpStatus.ServiceUnavailable)
    check(busy.headers[HttpHeader.RetryAfter]?.root == "120")
}
// endregion error-http-test

// region error-rethrow
@Serializable
data class DateInput(val text: String)

object ErrorsDateServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val parse = path.path("parse-date").post bind ApiHttpHandler(
        summary = "Parse date",
        auth = noAuth,
        errorCases = listOf(LSError(http = 400, detail = "invalid-date", message = "Not an ISO date")),
        implementation = { input: DateInput ->
            try {
                kotlinx.datetime.LocalDate.parse(input.text).toString()
            } catch (e: IllegalArgumentException) {
                throw BadRequestException(detail = "invalid-date", message = "Not an ISO date", cause = e)
            }
        }
    )
}
// endregion error-rethrow
