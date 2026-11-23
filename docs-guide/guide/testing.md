# Testing

`SERVER.testBlocking { }` starts your server in memory, with no port, network, or external services, and runs a test
against it.  Endpoints, tasks, sockets and interceptors run the same code they run in production.

<!-- sample: com/lightningkite/lightningserver/guide/samples/TestingSamples.kt#testing-full-example -->
```kotlin
class TestingGreetTest {
    @Test
    fun greetsByName() = TestingServer.testBlocking(settings = {}) {
        assertEquals("Hello, Alice!", TestingServer.greet.test(null, TestGreetRequest("Alice")).greeting)
    }

    @Test
    fun rejectsBlankName() = TestingServer.testBlocking(settings = {}) {
        val error = assertFailsWith<HttpStatusException> { TestingServer.greet.test(null, TestGreetRequest("")) }
        assertEquals("empty-name", error.detail)
    }
}
```

Each `testBlocking` call builds a fresh runtime, so in-memory databases and caches start empty in every test.  The
block is a suspend lambda; `testBlocking` blocks the calling thread until it finishes, which is what a plain `@Test`
method needs.  (`SERVER.test { }` is the same with a non-suspend block.)

The server under test:

<!-- sample: com/lightningkite/lightningserver/guide/samples/TestingSamples.kt#testing-server -->
```kotlin
@Serializable
data class TestGreetRequest(val name: String)

@Serializable
data class TestGreetResponse(val greeting: String)

@Serializable
data class TestMember(override val _id: Uuid = Uuid.random(), val name: String) : HasId<Uuid>

object TestMemberAuth : PrincipalType<TestMember, Uuid> {
    override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
    override val subjectSerializer: KSerializer<TestMember> = TestMember.serializer()

    context(server: ServerRuntime)
    override suspend fun fetch(id: Uuid): TestMember =
        TestingServer.members().get(id) ?: throw NotFoundException("Member not found")
}

object TestingServer : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val members = database.registerTable<TestMember>("Member")

    init {
        registerBasicMediaTypeCoders()
        register(TestMemberAuth)
    }

    val hello = path.path("hello").get bind HttpHandler {
        HttpResponse.plainText("Hello!")
    }

    val greet = path.path("greet").post bind ApiHttpHandler(
        summary = "Greet someone",
        auth = noAuth,
        successCode = HttpStatus.OK,
        errorCases = listOf(LSError(http = 400, detail = "empty-name", message = "Name must not be blank")),
        implementation = { input: TestGreetRequest ->
            if (input.name.isBlank()) throw BadRequestException(detail = "empty-name", message = "Name must not be blank")
            TestGreetResponse(greeting = "Hello, ${input.name}!")
        }
    )

    val profile = path.path("profile").get bind ApiHttpHandler(
        summary = "Get my profile",
        auth = TestMemberAuth.require(),
        successCode = HttpStatus.OK,
        errorCases = emptyList(),
        implementation = { _: Unit -> auth.fetch() }
    )
}
```

Imports:

<!-- sample: com/lightningkite/lightningserver/guide/samples/TestingSamples.kt#testing-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.*
```

Keep `kotlinx.serialization.builtins.serializer` explicit: the `kotlinx.serialization.*` wildcard alone doesn't
bring in `Uuid.serializer()`.

## Settings

<!-- sample: com/lightningkite/lightningserver/guide/samples/TestingSamples.kt#testing-settings -->
```kotlin
fun testingSettingsTest() = TestingServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    assertEquals(0, TestingServer.members().count())
}
```

The `settings` block sets any `ServerSetting` before the server starts (`set` is from
`com.lightningkite.lightningserver.settings`).  Settings you don't set keep their declared defaults; for databases and
caches that's `"ram"`, an in-process implementation.  See [Settings](settings.md).

## Calling endpoints

| Call | Goes through | Returns |
|---|---|---|
| `handler.test()` on an `HttpHandler` | The full HTTP pipeline: routing, HTTP interceptors, exception handler | `HttpResponse` |
| `endpoint.test(auth, input)` on an `ApiHttpHandler` | The endpoint, called as server code: input validation, the auth requirement, execution interceptors | The typed output, or the thrown exception |
| `socket.test()` on a `WebSocketHandler` | The socket interceptors; see [WebSockets](websockets.md) | A test connection |

Handlers with path arguments take them first: `getUser.test(userId)`, `getUser.test(userId, auth, Unit)`.  The raw
`test()` also takes `headers`, `queryParameters`, `body`, `domain`, `protocol` and `sourceIp`.

### Plain handlers

<!-- sample: com/lightningkite/lightningserver/guide/samples/TestingSamples.kt#testing-plain-handler -->
```kotlin
fun testingPlainHandlerTest() = TestingServer.testBlocking(settings = {}) {
    val response = TestingServer.hello.test()
    assertEquals(HttpStatus.OK, response.status)
    assertEquals("Hello!", response.body?.text())
}
```

### Authenticated endpoints

<!-- sample: com/lightningkite/lightningserver/guide/samples/TestingSamples.kt#testing-auth -->
```kotlin
fun testingAuthTest() = TestingServer.testBlocking(settings = {}) {
    val alice = TestMember(name = "Alice")
    TestingServer.members().insertOne(alice)

    val profile = TestingServer.profile.test(TestMemberAuth.testAuth(alice), Unit)
    assertEquals("Alice", profile.name)
}
```

`testAuth(subject)` builds an `Authentication` without a session or token.  For `noAuth` endpoints pass `null`.
Services such as `members()` can be used directly inside the block to set up data.

### Errors

The typed `test()` throws what the endpoint threw, so assert on the exception (`status`, `detail`), as in
`rejectsBlankName` above.  To check what a client receives, send a raw request through the HTTP pipeline:

<!-- sample: com/lightningkite/lightningserver/guide/samples/TestingSamples.kt#testing-wire -->
```kotlin
fun testingWireTest() = TestingServer.testBlocking(settings = {}) {
    val response = TestingServer.greet.test(body = TypedData.text("""{"name":""}""", MediaType.Application.Json))
    assertEquals(HttpStatus.BadRequest, response.status)
}
```

The raw pipeline needs `registerBasicMediaTypeCoders()` on the server to read and write JSON bodies, error responses included; without it, errors come back as plain text (with their real status) and JSON bodies can't be read.

## Code that needs a runtime

Some calls need a running execution rather than just the server: launching a task, `now()`, anything declared
`context(ServerRuntime)`.  `execute { }` runs a block as the test's own execution:

```kotlin
execute { TasksServer.sendWelcomeEmail.launch(WelcomeEmail("bob@example.com", "Bob")) }
```

Launched tasks run inline, so their effects are visible on the next line.  Startup and pre-deploy tasks don't run on
their own; call `executeStartupTasks()` or `executePreDeployTasks()`.  A schedule's work runs with
`schedule.executeWithMetrics()`.  See [Tasks](tasks.md#testing-a-task) and
[Schedules](schedules.md#testing-a-schedule).

## Controlling time

<!-- sample: com/lightningkite/lightningserver/guide/samples/TestingSamples.kt#testing-clock -->
```kotlin
fun testingClockTest() {
    var time = Instant.parse("2026-01-01T00:00:00Z")
    val clock = object : Clock {
        override fun now(): Instant = time
    }
    TestingServer.testBlocking(settings = {}, clock = { clock }) {
        val before = execute { now() }
        time += 2.hours
        assertEquals(2.hours, execute { now() } - before)
    }
}
```

`clock` replaces the server's clock, which `now()` and token issue times read.  It doesn't change how long
`delay` waits.

## Build checks

A build check validates the whole server once it's assembled, so a misconfiguration fails every test (and startup)
instead of a request in production:

<!-- sample: com/lightningkite/lightningserver/guide/samples/TestingSamples.kt#testing-build-check -->
```kotlin
object LockCheckedServer : ServerBuilder() {
    init {
        buildCheck { definition ->
            for ((path, schedule) in definition.schedules)
                check(schedule.timeout < 1.hours) { "$path can outlive its schedule lock" }
        }
    }
}
```

`buildCheck` receives the root `ServerDefinition`, even when registered in a module, after every module is
initialized; modules' checks run first.  It has no runtime, so it can't read settings or touch services.  Throw to fail
`build()`.  Every `testBlocking` builds the server, so any test catches a failing check; a test of its own makes the
point explicit:

<!-- sample: com/lightningkite/lightningserver/guide/samples/TestingSamples.kt#testing-build-check-test -->
```kotlin
fun testingBuildCheckTest() {
    LockCheckedServer.build()
}
```

## Next

- [Typed endpoints](typed-endpoints.md): the endpoints the typed `test()` calls.
- [WebSockets](websockets.md): testing socket handlers.
- [CORS and interceptors](cors-interceptors.md): testing headers that interceptors add.
