package com.lightningkite.lightningserver.guide.samples

// region testing-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.cache.*
import com.lightningkite.services.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.*
// endregion testing-imports

// region testing-server-types
@Serializable
data class GreetRequest(val name: String)

@Serializable
data class GreetResponse(val greeting: String)

@Serializable
data class Member(
    override val _id: Uuid = Uuid.random(),
    val name: String,
) : HasId<Uuid> {
    companion object : PrincipalType<Member, Uuid> {
        override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
        override val subjectSerializer: KSerializer<Member> = Member.serializer()

        // In-memory store for testing — fetch() looks up by ID here.
        // A production implementation would query a database table instead.
        val store = mutableMapOf<Uuid, Member>()

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): Member =
            store[id] ?: throw com.lightningkite.lightningserver.NotFoundException("Member not found")
    }
}
// endregion testing-server-types

// region testing-server
object TestingServer : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())

    init {
        // register() is an extension on ServerBuilder from the auth module.
        // It makes this PrincipalType discoverable when deserializing auth tokens.
        register(Member)
    }

    // Plain HttpHandler — no type safety, returns an HttpResponse directly
    val hello = path.path("hello").get bind HttpHandler {
        HttpResponse.plainText("Hello!")
    }

    // noAuth typed endpoint — auth = noAuth means no authentication is required.
    // The first argument to ApiHttpHandler.test() is the auth token; pass null for noAuth.
    val greet = path.path("greet").post bind ApiHttpHandler(
        summary = "Greet someone",
        auth = noAuth,
        successCode = HttpStatus.OK,
        errorCases = listOf(
            LSError(http = 400, detail = "empty-name", message = "Name must not be blank")
        ),
        implementation = { input: GreetRequest ->
            if (input.name.isBlank())
                throw BadRequestException(detail = "empty-name", message = "Name must not be blank")
            GreetResponse(greeting = "Hello, ${input.name}!")
        }
    )

    // Authenticated typed endpoint — auth = Member.require() means a Member token is required.
    // The first argument to ApiHttpHandler.test() must be a non-null Authentication<Member>.
    val profile = path.path("profile").get bind ApiHttpHandler(
        summary = "Get member profile",
        auth = Member.require(),
        successCode = HttpStatus.OK,
        errorCases = emptyList(),
        implementation = { _: Unit ->
            // auth is Authentication<Member>; fetch() loads the full Member from the store
            auth.fetch()
        }
    )
}
// endregion testing-server

// region testing-plain-handler
// The settings lambda configures each ServerSetting before the runtime starts.
// "ram" is the built-in URL for the in-process cache — no external dependencies.
fun plainHandlerTest() = TestingServer.testBlocking(settings = { cache set Cache.Settings("ram") }) {
    // HttpHandler.test() returns an HttpResponse.
    // Inspect .status and .body?.text() to assert the outcome.
    val response = TestingServer.hello.test()
    check(response.status == HttpStatus.OK)
    check(response.body?.text() == "Hello!")
}
// endregion testing-plain-handler

// region testing-noauth-typed
fun noAuthTypedTest() = TestingServer.testBlocking(settings = { cache set Cache.Settings("ram") }) {
    // ApiHttpHandler.test() takes (auth, input) and returns the typed OUTPUT directly —
    // no HttpResponse to unwrap, no JSON to parse.
    //
    // For noAuth endpoints (USER = Nothing?), the auth argument must be null.
    val result = TestingServer.greet.test(null, GreetRequest("Alice"))
    check(result.greeting == "Hello, Alice!")
}
// endregion testing-noauth-typed

// region testing-auth-typed
fun authTypedTest() = TestingServer.testBlocking(settings = { cache set Cache.Settings("ram") }) {
    val alice = Member(name = "Alice")
    Member.store[alice._id] = alice  // seed the in-memory store so fetch() finds her

    // testAuth() creates a synthetic Authentication<Member> for the test.
    // It must be called inside a test {} block because it needs a ServerRuntime in context.
    val aliceAuth = Member.testAuth(alice)

    // For authenticated endpoints (USER is non-nullable), pass a non-null Authentication.
    val result = TestingServer.profile.test(aliceAuth, Unit)
    check(result.name == "Alice")

    Member.store.clear()  // clean up so tests don't bleed state
}
// endregion testing-auth-typed

// region testing-error-path
fun errorPathTest() = TestingServer.testBlocking(settings = { cache set Cache.Settings("ram") }) {
    // ApiHttpHandler.test() propagates HttpStatusException directly as a Kotlin exception.
    // It does NOT serialize to/from HTTP, so the exception is exactly what the handler threw.
    // Catch HttpStatusException and inspect .status.code and .detail to verify the right error fired.
    try {
        TestingServer.greet.test(null, GreetRequest(""))
        error("Expected BadRequestException to be thrown")
    } catch (e: HttpStatusException) {
        check(e.status.code == 400)
        check(e.detail == "empty-name")
    }
}
// endregion testing-error-path

// region testing-testblocking
// SERVER.testBlocking { } is the recommended entry point for tests. Its action is a
// suspend lambda, so you call suspend APIs (.test(), database/cache operations) directly —
// no runBlocking wrapper. The name signals that it blocks the calling thread until the
// action finishes, which is exactly what an ordinary (non-suspend) @Test method needs.
fun testBlockingExplanation() = TestingServer.testBlocking(settings = { cache set Cache.Settings("ram") }) {
    // .test() is a suspend call and works here because the action lambda is suspend.
    val response = TestingServer.hello.test()
    check(response.status == HttpStatus.OK)
}
// endregion testing-testblocking

// region testing-full-example
// A complete, copy-pasteable test class.
// @Test marks each method for the test runner. testBlocking {} runs the suspend action
// to completion on the calling thread, so each non-suspend @Test method stays simple.
class GreetServerTest {
    @Test
    fun `greet returns greeting for valid name`() = TestingServer.testBlocking(settings = { cache set Cache.Settings("ram") }) {
        val result = TestingServer.greet.test(null, GreetRequest("Alice"))
        check(result.greeting == "Hello, Alice!")
    }

    @Test
    fun `greet rejects blank name`() = TestingServer.testBlocking(settings = { cache set Cache.Settings("ram") }) {
        try {
            TestingServer.greet.test(null, GreetRequest(""))
            error("Expected exception")
        } catch (e: HttpStatusException) {
            check(e.status.code == 400)
            check(e.detail == "empty-name")
        }
    }
}
// endregion testing-full-example
