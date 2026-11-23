package com.lightningkite.lightningserver.guide.samples

// region validation-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.*
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

// region validation-server
@Serializable
data class NewAccount(
    @MaxLength(50) val name: String,
    @ExpectedPattern("[a-z0-9_]{3,20}") val handle: String,
    @IntegerRange(13, 120) val age: Int,
    // @MaxSize limits the list; @MaxLength applies to each element.
    @MaxSize(10) @MaxLength(20) val tags: List<String> = emptyList(),
)

object ValidatedServer : ServerBuilder() {
    override val annotationValidators = AnnotationValidators.StandardWithExternalModule

    init { registerBasicMediaTypeCoders() }

    val createAccount = path.path("accounts").post bind ApiHttpHandler(
        summary = "Create account",
        auth = noAuth,
        successCode = HttpStatus.Created,
        errorCases = listOf(LSError(http = 400, detail = "validation-failed", message = "Input failed validation")),
        implementation = { input: NewAccount -> "Created ${input.handle}" }
    )
}
// endregion validation-server

// region validation-test
fun validationTest() = ValidatedServer.testBlocking(settings = {}) {
    val valid = NewAccount(name = "Alice", handle = "alice", age = 30)
    check(ValidatedServer.createAccount.test(null, valid) == "Created alice")

    try {
        ValidatedServer.createAccount.test(null, valid.copy(handle = "A!", age = 7))
        error("Expected validation to fail")
    } catch (e: BadRequestException) {
        check(e.detail == "validation-failed")
        check("handle" in e.data && "age" in e.data)
    }
}
// endregion validation-test

// region validation-http-test
fun validationHttpTest() = ValidatedServer.testBlocking(settings = {}) {
    val response = ValidatedServer.createAccount.test(
        body = TypedData.text("""{"name":"Bob","handle":"bob","age":200}""", MediaType.Application.Json)
    )
    check(response.status == HttpStatus.BadRequest)
}
// endregion validation-http-test

// region validation-custom
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@ValidationAnnotation
annotation class MustStartWith(val prefix: String)

@Serializable
data class Coupon(@MustStartWith("SAVE") val code: String)

object CouponServer : ServerBuilder() {
    override val annotationValidators = Runtime.Cached {
        AnnotationValidators.StandardWithExternalModule() + AnnotationValidators {
            // `this` is the annotation; return an error message, or null when the value is valid.
            validate<MustStartWith, String> { value ->
                if (value.startsWith(prefix)) null else "Must start with '$prefix'"
            }
        }
    }

    init { registerBasicMediaTypeCoders() }

    val redeem = path.path("redeem").post bind ApiHttpHandler(
        summary = "Redeem coupon",
        auth = noAuth,
        implementation = { input: Coupon -> "Redeemed ${input.code}" }
    )
}
// endregion validation-custom

// region validation-custom-test
fun customValidationTest() = CouponServer.testBlocking(settings = {}) {
    check(CouponServer.redeem.test(null, Coupon("SAVE10")) == "Redeemed SAVE10")
    try {
        CouponServer.redeem.test(null, Coupon("FREE"))
        error("Expected validation to fail")
    } catch (e: BadRequestException) {
        check(e.message == "code: Must start with 'SAVE'")
    }
}
// endregion validation-custom-test

// region validation-manual
object ImportServer : ServerBuilder() {
    override val annotationValidators = AnnotationValidators.StandardWithExternalModule

    init { registerBasicMediaTypeCoders() }

    val import = path.path("import").post bind HttpHandler { request ->
        val account = request.body?.parse(NewAccount.serializer()) ?: throw BadRequestException("Missing body")
        serverRuntime.validators.assertValidOrBadRequest(NewAccount.serializer(), account)
        HttpResponse.plainText("Imported ${account.handle}")
    }
}
// endregion validation-manual

// region validation-manual-test
fun manualValidationTest() = ImportServer.testBlocking(settings = {}) {
    val response = ImportServer.import.test(
        body = TypedData.text("""{"name":"Bob","handle":"bob","age":200}""", MediaType.Application.Json)
    )
    check(response.status == HttpStatus.BadRequest)
}
// endregion validation-manual-test
