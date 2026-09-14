package com.lightningkite.lightningserver.guide.samples

// region validation-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.validation.*
import kotlinx.serialization.*
// endregion validation-imports

// region validated-model
@Serializable
data class CreateUserRequest(
    @MaxLength(50) val name: String,
    @MaxLength(254) val email: String,
    @IntegerRange(0, 120) val age: Int,
    // @MaxSize(10) bounds the list to 10 elements; @MaxLength(20) cascades to each element.
    @MaxSize(10) @MaxLength(20) val tags: List<String>,
)
// endregion validated-model

// region validation-server
object ValidationServer : ServerBuilder() {
    // StandardWithExternalModule includes the built-in constraint annotations
    // (@MaxLength, @MaxSize, @IntegerRange, @FloatRange, @ExpectedPattern) and
    // includes the server's serializers module so custom serializable types
    // can also participate in validation.
    override val annotationValidators = AnnotationValidators.StandardWithExternalModule

    init {
        // Required when testing through the full HTTP pipeline (HttpHandler.test()),
        // including for validation-failure error responses.
        registerBasicMediaTypeCoders()
    }

    // POST /users — accepts a CreateUserRequest; the framework validates the input
    // against all declared constraint annotations before calling the implementation.
    val createUser = path.path("users").post bind ApiHttpHandler(
        summary = "Create a user",
        description = "Creates a new user. Constraint annotations are checked before the implementation runs.",
        auth = noAuth,
        successCode = HttpStatus.Created,
        errorCases = listOf(
            // Declare the validation-failed case so generated SDKs can pattern-match on it.
            LSError(http = 400, detail = "validation-failed", message = "Input failed constraint validation")
        ),
        implementation = { input: CreateUserRequest ->
            // This lambda only runs when every constraint passes.
            // Invalid input never reaches here — the framework rejects it with 400 first.
            "Created user: ${input.name}"
        }
    )
}
// endregion validation-server

// region validation-reject-test
fun validationRejectTest() = ValidationServer.testBlocking(settings = {}) {
    // Encode a CreateUserRequest whose name exceeds @MaxLength(50).
    val body = TypedData.text(
        serverRuntime.externalSerialization.json.encodeToString(
            CreateUserRequest.serializer(),
            CreateUserRequest(name = "A".repeat(51), email = "user@example.com", age = 25, tags = emptyList())
        ),
        MediaType.Application.Json
    )
    // HttpHandler.test(body = ...) drives the full HTTP pipeline, including the validation
    // step that runs before the implementation lambda.
    // The typed ApiHttpHandler.test(auth, input) helper bypasses validation — always use
    // HttpHandler.test() when testing constraint enforcement.
    val response = ValidationServer.createUser.test(body = body)
    check(response.status.code == 400)
}
// endregion validation-reject-test

// region validation-pass-test
fun validationPassTest() = ValidationServer.testBlocking(settings = {}) {
    val body = TypedData.text(
        serverRuntime.externalSerialization.json.encodeToString(
            CreateUserRequest.serializer(),
            CreateUserRequest(name = "Alice", email = "alice@example.com", age = 30, tags = emptyList())
        ),
        MediaType.Application.Json
    )
    val response = ValidationServer.createUser.test(body = body)
    check(response.status.code == 201)
}
// endregion validation-pass-test
