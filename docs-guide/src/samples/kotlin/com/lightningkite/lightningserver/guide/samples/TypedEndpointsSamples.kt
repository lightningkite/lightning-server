package com.lightningkite.lightningserver.guide.samples

// region typed-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.typed.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
// endregion typed-imports

// region divide-types
@Serializable
data class DivideRequest(val numerator: Double, val denominator: Double)

@Serializable
data class DivideResponse(val result: Double)
// endregion divide-types

// region divide-server
object DivideServer : ServerBuilder() {

    // POST /divide — divides two numbers; declares two error cases
    val divide = path.path("divide").post bind ApiHttpHandler(
        summary = "Divide two numbers",
        description = "Returns the quotient. Rejects non-finite inputs and division by zero.",
        auth = noAuth,
        successCode = HttpStatus.OK,
        errorCases = listOf(
            // errorCases appear in the generated OpenAPI spec and SDK.
            // They do NOT enforce anything at runtime — your implementation must throw.
            LSError(http = 400, detail = "division-by-zero", message = "Denominator must not be zero"),
            LSError(http = 400, detail = "infinite-input", message = "Inputs must be finite numbers")
        ),
        implementation = { input: DivideRequest ->
            if (!input.numerator.isFinite() || !input.denominator.isFinite())
                throw BadRequestException(
                    detail = "infinite-input",
                    message = "Inputs must be finite numbers"
                )
            if (input.denominator == 0.0)
                throw BadRequestException(
                    detail = "division-by-zero",
                    message = "Denominator must not be zero"
                )
            DivideResponse(result = input.numerator / input.denominator)
        }
    )
}
// endregion divide-server

// region divide-success-test
fun divideSuccessTest() = runBlocking {
    DivideServer.test(settings = {}) {
        val result = DivideServer.divide.test(null, DivideRequest(10.0, 4.0))
        check(result.result == 2.5)
    }
}
// endregion divide-success-test

// region divide-error-test
fun divideErrorTest() = runBlocking {
    DivideServer.test(settings = {}) {
        // When an ApiHttpHandler implementation throws an HttpStatusException,
        // the typed .test() extension propagates it directly as a Kotlin exception.
        // Catch HttpStatusException and inspect .status.code and .detail to verify
        // the right error fired.
        try {
            DivideServer.divide.test(null, DivideRequest(1.0, 0.0))
            error("Expected an exception")
        } catch (e: HttpStatusException) {
            check(e.status.code == 400)
            check(e.detail == "division-by-zero")
        }
    }
}
// endregion divide-error-test

// region success-code-types
@Serializable
data class NoteRequest(val text: String)

@Serializable
data class NoteResponse(val id: String, val text: String)
// endregion success-code-types

// region success-code-server
object NoteServer : ServerBuilder() {

    // POST /notes — uses HttpStatus.Created (201) instead of the default 200
    val create = path.path("notes").post bind ApiHttpHandler(
        summary = "Create a note",
        description = "Stores a new note and returns it with an assigned id.",
        auth = noAuth,
        // successCode defaults to HttpStatus.OK (200); override for creation endpoints.
        successCode = HttpStatus.Created,
        errorCases = emptyList(),
        implementation = { input: NoteRequest ->
            NoteResponse(id = "note-1", text = input.text)
        }
    )
}
// endregion success-code-server

// region success-code-test
fun successCodeTest() = runBlocking {
    NoteServer.test(settings = {}) {
        // ApiHttpHandler.test() returns the typed output directly.
        // The HTTP status code is used by real clients; in unit tests confirm
        // the response fields instead of the status.
        val result = NoteServer.create.test(null, NoteRequest("hello"))
        check(result.text == "hello")
        check(result.id.isNotEmpty())
    }
}
// endregion success-code-test

// region examples-field
object ExamplesServer : ServerBuilder() {
    // ApiHttpHandler.Example values are documentation only — they appear in the generated
    // OpenAPI spec and SDK but are NOT run or asserted automatically.
    // Write a real test alongside any example you provide.
    val echo = path.path("echo").post bind ApiHttpHandler(
        summary = "Echo",
        description = "Returns the input unchanged.",
        auth = noAuth,
        successCode = HttpStatus.OK,
        errorCases = emptyList(),
        examples = listOf(
            ApiHttpHandler.Example(
                input = EchoRequest("hello"),
                output = EchoResponse(echo = "hello", length = 5),
                name = "Basic echo",
                notes = "Showing roundtrip for a short string."
            )
        ),
        implementation = { input: EchoRequest ->
            EchoResponse(echo = input.message, length = input.message.length)
        }
    )
}
// endregion examples-field
