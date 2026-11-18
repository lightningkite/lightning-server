package com.lightningkite.lightningserver.guide.samples

// region testing-imports
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
// endregion testing-imports

// region testing-server
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
// endregion testing-server

// region testing-full-example
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
// endregion testing-full-example

// region testing-settings
fun testingSettingsTest() = TestingServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    assertEquals(0, TestingServer.members().count())
}
// endregion testing-settings

// region testing-plain-handler
fun testingPlainHandlerTest() = TestingServer.testBlocking(settings = {}) {
    val response = TestingServer.hello.test()
    assertEquals(HttpStatus.OK, response.status)
    assertEquals("Hello!", response.body?.text())
}
// endregion testing-plain-handler

// region testing-auth
fun testingAuthTest() = TestingServer.testBlocking(settings = {}) {
    val alice = TestMember(name = "Alice")
    TestingServer.members().insertOne(alice)

    val profile = TestingServer.profile.test(TestMemberAuth.testAuth(alice), Unit)
    assertEquals("Alice", profile.name)
}
// endregion testing-auth

// region testing-wire
fun testingWireTest() = TestingServer.testBlocking(settings = {}) {
    val response = TestingServer.greet.test(body = TypedData.text("""{"name":""}""", MediaType.Application.Json))
    assertEquals(HttpStatus.BadRequest, response.status)
}
// endregion testing-wire

// region testing-clock
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
// endregion testing-clock

// region testing-build-check
object LockCheckedServer : ServerBuilder() {
    init {
        buildCheck { definition ->
            for ((path, schedule) in definition.schedules)
                check(schedule.timeout < 1.hours) { "$path can outlive its schedule lock" }
        }
    }
}
// endregion testing-build-check

// region testing-build-check-test
fun testingBuildCheckTest() {
    LockCheckedServer.build()
}
// endregion testing-build-check-test
