# Condensed Guide

Every Kotlin block below is compiled and tested
(`docs-guide/src/samples/kotlin/com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt`).
Each section links to its full chapter.

## Set up the project

Gradle, server module (coordinates match this repo's modules; versions are yours):

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation("com.lightningkite.lightningserver:core:$lightningServerVersion")
    implementation("com.lightningkite.lightningserver:typed:$lightningServerVersion")
    implementation("com.lightningkite.lightningserver:auth:$lightningServerVersion")
    implementation("com.lightningkite.lightningserver:sessions-email:$lightningServerVersion")
    implementation("com.lightningkite.lightningserver:files:$lightningServerVersion")
    implementation("com.lightningkite.lightningserver:engine-ktor:$lightningServerVersion")
    ksp("com.lightningkite.services:database-processor:$serviceAbstractionsVersion")
    testImplementation("com.lightningkite.lightningserver:engine-local:$lightningServerVersion")
}
```

Imports used by every snippet on this page:

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.cors.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.engine.awsserverless.*
import com.lightningkite.lightningserver.engine.ktor.*
import com.lightningkite.lightningserver.files.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.ratelimit.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.sessions.*
import com.lightningkite.lightningserver.sessions.proofs.*
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.terraform.awsserverless.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.cache.*
import com.lightningkite.services.cache.dynamodb.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import com.lightningkite.services.database.validation.*
import com.lightningkite.services.email.*
import com.lightningkite.services.files.*
import com.lightningkite.services.files.s3.*
import com.lightningkite.services.kfile.*
import com.lightningkite.services.terraform.*
import io.ktor.server.netty.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import software.amazon.awssdk.regions.Region
import kotlin.reflect.KClass
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.*
```

Full chapter: [Project Structure](project-structure.md).

## Define the server

One `ServerBuilder` object holds settings and mounts the rest of the API:

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#server -->
```kotlin
object Server : ServerBuilder() {
    // Turns on @MaxLength, @IntegerRange, ... checks for every typed endpoint's input.
    override val annotationValidators = AnnotationValidators.StandardWithExternalModule

    // Each setting is a key in settings.json; the defaults are in-memory or local.
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())
    val email = setting("email", EmailService.Settings())
    val files = setting("files", ExternalFileSystem.Settings())
    val supportAddress = setting("supportAddress", "support@example.com")

    init {
        registerBasicMediaTypeCoders()
        // Implementations outside the defaults register their URL scheme when first referenced.
        DynamoDbCache
        S3ExternalFileSystem
    }

    // Declare settings above the includes: the included builders read them while Server initializes.
    val routes = path include Routes
    val quotes = path.path("quotes") include Quotes
    val users = path.path("users") include Users
    val posts = path.path("posts") include Posts
    val account = path.path("account") include Account
    val auth = path.path("auth") include Login
    val attachments = path.path("attachments") include Attachments
    val uploads = path.path("upload") include GlobalUploadEndpoints(files, database, Runtime.Constant(listOf()), authOptions = UserAuth.require())
    val chat = path.path("chat") include Chat
    val jobs = path.path("jobs") include Jobs
    val meta = path.path("meta") include MetaEndpoints("com.example", database, cache)
}
```

Full chapters: [Overview](overview.md), [Services & Settings](services.md).

## Add GET/POST routes with path args

`path.path("x").arg<T>("name").get bind HttpHandler { }`; read args as `request.path.arg1`, `arg2`, ...

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#routes -->
```kotlin
object Routes : ServerBuilder() {
    // GET /
    val root = path.get bind HttpHandler {
        HttpResponse.plainText("OK")
    }

    // GET /hello/{name}?shout=true
    val hello = path.path("hello").arg<String>("name").get bind HttpHandler { request ->
        val greeting = "Hello, ${request.path.arg1}!"
        val shout = request.queryParameters["shout"] == "true"
        HttpResponse.plainText(if (shout) greeting.uppercase() else greeting)
    }

    // POST /echo with a raw text body
    val echo = path.path("echo").post bind HttpHandler { request ->
        HttpResponse.plainText(request.body!!.text())
    }
}
```

Full chapter: [Routing](routing.md).

## Add a typed endpoint

Input and output are `@Serializable` types; `errorCases` documents errors, the throw enforces them:

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#typed -->
```kotlin
@Serializable
data class Quote(val text: String, val author: String)

object Quotes : ServerBuilder() {
    private val all = listOf(Quote("Complexity very, very bad.", "grug"))

    // POST /quotes/search: JSON body in, JSON out; documented in OpenAPI and the generated SDK.
    val search = path.path("search").post bind ApiHttpHandler(
        summary = "Search quotes",
        auth = noAuth,
        implementation = { text: String -> all.filter { it.text.contains(text, ignoreCase = true) } }
    )

    // GET /quotes/{index}: path args come from `route`.
    val byIndex = path.arg<Int>("index").get bind ApiHttpHandler(
        summary = "Quote by index",
        auth = noAuth,
        errorCases = listOf(LSError(http = 404, detail = "no-quote", message = "No quote at that index")),
        implementation = { _: Unit ->
            all.getOrNull(route.arg1)
                ?: throw NotFoundException(detail = "no-quote", message = "No quote at that index")
        }
    )
}
```

Full chapter: [Typed Endpoints](typed-endpoints.md).

## Define models

`@Queryable` generates the field paths the query DSL uses (needs the KSP processor):

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#models -->
```kotlin
@Serializable
enum class Role { Member, Admin }

@Queryable
data class User(
    override val _id: Uuid = Uuid.random(),
    val email: String,
    val role: Role = Role.Member,
) : HasId<Uuid>

@Queryable
data class Post(
    override val _id: Uuid = Uuid.random(),
    val author: Uuid,
    @MaxLength(100) val title: String,
    val body: String = "",
    val createdAt: Instant = Clock.System.now(),
) : HasId<Uuid>
```

Full chapters: [Database](database.md), [Validation](validation.md).

## Generate REST endpoints with permissions

`ModelPermissions` conditions filter which rows each caller can create, read, update and delete:

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#rest-posts -->
```kotlin
object Posts : ServerBuilder() {
    val info = Server.database.modelInfo<User, Post, Uuid>(
        auth = UserAuth.require(),
        tableName = "Post",
        permissions = {
            val me = auth.fetch()
            val mine = Condition<Post> { it.author eq me._id }
            val admin = Condition(me.role == Role.Admin)
            ModelPermissions(create = mine, read = Condition.Always, update = mine or admin, delete = mine or admin)
        }
    )

    val rest = path include ModelRestEndpoints(info)
}
```

Optional auth (`require() or AuthRequirement.None`) with `authOrNull`:

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#rest-users -->
```kotlin
object Users : ServerBuilder() {
    val info = Server.database.modelInfo<User?, User, Uuid>(
        auth = UserAuth.require() or AuthRequirement.None,
        tableName = "User",
        permissions = {
            val me = authOrNull?.fetch()
            val self = Condition<User> { it._id eqNn me?._id }
            val admin = Condition(me?.role == Role.Admin)
            ModelPermissions(create = admin, read = self or admin, update = admin, delete = admin)
        }
    )

    // GET/POST /users, GET/PUT/PATCH/DELETE /users/{id}, POST /users/query, /count, /bulk, ...
    val rest = path include ModelRestEndpoints(info)
}
```

Full chapter: [Model REST Endpoints](model-rest.md).

## Query the database

Conditions, sorting, modifications, counts and deletes against a `Table`:

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#queries -->
```kotlin
context(server: ServerRuntime)
suspend fun queryExamples(author: Uuid): List<Post> {
    // table() is system access: no permissions applied.
    val posts = Posts.info.table()

    posts.insertOne(Post(author = author, title = "Hello"))

    val recent = posts.find(
        condition = Condition { (it.author eq author) and (it.title contains "Hell") },
        orderBy = sort { it.createdAt.descending() },
        limit = 20,
    ).toList()

    posts.updateMany(
        Condition { it.author eq author },
        Modification { it.body assign "edited" },
    )

    val lastDay = posts.count(Condition { it.createdAt gt Clock.System.now() - 1.days })
    check(lastDay == 1)

    posts.deleteMany(Condition { it.title inside setOf("spam", "junk") })
    return recent
}
```

Other operators: `neq`, `gte`, `lte`, `notInside`, `any { }`, `all { }`, `or`; modifications `+=`, `-=`, `coerceAtLeast`, `forEach { }`.

Full chapter: [Database & Query DSL](database.md).

## Define the user principal

A `PrincipalType` tells auth how to load a user from a token or a proof:

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#principal -->
```kotlin
object UserAuth : PrincipalType<User, Uuid> {
    override val idSerializer = Uuid.serializer()
    override val subjectSerializer = User.serializer()

    context(server: ServerRuntime)
    override suspend fun fetch(id: Uuid): User =
        Users.info.table().get(id) ?: throw NotFoundException("No such user")

    // Proofs name the user by property; creating the user here makes email login double as sign-up.
    context(server: ServerRuntime)
    override suspend fun fetchByProperty(property: String, value: String): User? = when (property) {
        "email" -> Users.info.table().findOne(Condition { it.email eq value })
            ?: Users.info.table().insertOne(User(email = value))
        else -> super.fetchByProperty(property, value)
    }
}
```

Full chapter: [Authentication & Sessions](auth.md).

## Add a protected endpoint

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#protected -->
```kotlin
object Account : ServerBuilder() {
    // Requires a session. `auth.id` is free; `auth.fetch()` loads the row.
    val me = path.path("me").get bind ApiHttpHandler(
        summary = "Current user",
        auth = UserAuth.require(),
        implementation = { _: Unit -> auth.fetch() }
    )

    // Optional auth: `authOrNull` is null for anonymous callers.
    val greet = path.path("greet").get bind ApiHttpHandler(
        summary = "Greet",
        auth = UserAuth.require() or AuthRequirement.None,
        implementation = { _: Unit -> authOrNull?.fetch()?.let { "Hi, ${it.email}" } ?: "Hi, stranger" }
    )

    // Role check: a hard 403, unlike ModelPermissions, which filters rows.
    val postCount = path.path("post-count").get bind ApiHttpHandler(
        summary = "Count all posts",
        auth = UserAuth.require(),
        implementation = { _: Unit ->
            if (auth.fetch().role != Role.Admin) throw ForbiddenException("Admins only")
            Posts.info.table().count()
        }
    )
}
```

## Log users in with proofs and sessions

Proofs (email PIN, password, ...) are exchanged for a session; the refresh token becomes a bearer token:

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#login -->
```kotlin
object Login : ServerBuilder() {
    val pins = PinHandler(Server.cache, "pins")

    // POST /auth/email/start (address -> key), POST /auth/email/prove (key + PIN -> Proof)
    val email = path.path("email") include EmailProofEndpoints(pins, Server.email, { to, pin ->
        Email(subject = "Your login code", to = listOf(EmailAddressWithName(to)), plainText = "Your code is $pin")
    })

    val password = path.path("password") include PasswordProofEndpoints(Server.database, Server.cache)

    // POST /auth/login (proofs -> refresh token), /auth/token/simple, /auth/self, ...
    // AuthEndpoints registers UserAuth itself; don't also call register(UserAuth).
    val sessions = path include object : AuthEndpoints<User, Uuid>(UserAuth, Server.database, Server.cache) {
        context(server: ServerRuntime)
        override suspend fun requiredProofStrengthFor(subject: User): Int = 5

        context(server: ServerRuntime)
        override suspend fun sessionExpiration(subject: User): Instant? = null

        context(server: ServerRuntime)
        override suspend fun sessionStaleAfter(subject: User): Duration? = 30.days
    }
}
```

Client flow: `POST /auth/email/start` → email arrives → `POST /auth/email/prove` → `POST /auth/login` → send
`Authorization: Bearer <token>`.

Full chapter: [Proof & Session](proof-session.md).

## Store files

`files().root.then(...)` is a file reference: `put`, `head`, `delete`, `signedUrl`.
`GlobalUploadEndpoints` (mounted once, in [Define the server](#define-the-server)) lets clients upload directly
and send the result as a `ServerFile` field.

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#files -->
```kotlin
object Attachments : ServerBuilder() {
    // POST /attachments: store text, return a signed download URL.
    val create = path.post bind ApiHttpHandler(
        summary = "Store an attachment",
        auth = UserAuth.require(),
        implementation = { content: String ->
            // Name files server-side; a client-chosen name invites path traversal.
            val file = Server.files().root.then("attachments", "${Uuid.random()}.txt")
            file.put(TypedData.text(content, MediaType.Text.Plain))
            file.signedUrl
        }
    )
}
```

## Push over WebSockets

A topic fans messages out to every subscribed connection:

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#websockets -->
```kotlin
object Chat : ServerBuilder() {
    val room = path.path("room").topic(String.serializer())

    // ws:// /chat: every frame a client sends goes to every connected client.
    val socket = path bind WebSocketHandler(
        storageSerializer = Unit.serializer(),
        willConnect = { },
        didConnect = { subscribe(room) },
        messageFromClient = { frame -> room.send(frame.text) },
        topicHandlers = { room bind { message -> send(message.value) } },
        disconnect = { },
    )
}
```

Full chapter: [WebSockets](websockets.md).

## Run background tasks and schedules

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#tasks -->
```kotlin
object Jobs : ServerBuilder() {
    // A background task; the path is only its identity, not an HTTP route.
    val welcome = path.path("welcome") bind Task { address: String ->
        Server.email().send(
            Email(subject = "Welcome!", to = listOf(EmailAddressWithName(address)), plainText = "Glad you're here.")
        )
    }

    // POST /jobs/signup: queue the task and return immediately.
    val signup = path.path("signup").post bind ApiHttpHandler(
        summary = "Sign up",
        auth = noAuth,
        implementation = { address: String -> welcome.launch(address) }
    )

    // Runs every hour on one instance.
    val cleanup = path.path("cleanup") bind ScheduledTask(frequency = 1.hours) {
        Posts.info.table().deleteMany(Condition { it.createdAt lt Clock.System.now() - 365.days })
    }
}
```

Full chapters: [Tasks](tasks.md), [Schedules](schedules.md).

## Cache values

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#caching -->
```kotlin
context(server: ServerRuntime)
suspend fun cacheExamples() {
    val cache = Server.cache()

    cache.set("greeting", "hello", timeToLive = 5.minutes)
    val greeting: String? = cache.get<String>("greeting")
    check(greeting == "hello")

    // Atomic counter; the key is created on first use.
    val hits = cache.add("hits", 1L)
    check(hits == 1L)

    // Write only if absent: a simple distributed lock.
    val gotLock = cache.setIfNotExists("lock:report", true, timeToLive = 1.minutes)
    check(gotLock)

    // One-time token: read and delete in one step.
    cache.set("verify:abc", "user@example.com")
    check(cache.getAndRemove<String>("verify:abc") == "user@example.com")
}
```

Full chapter: [Caching](caching.md).

## Return errors

Throw `BadRequestException`, `UnauthorizedException`, `ForbiddenException`, `NotFoundException` (each takes
`detail` and `message`); clients get the status plus an `LSError` JSON body.
See `byIndex` in [Add a typed endpoint](#add-a-typed-endpoint) and its test in
[Test without a server](#test-without-a-server).

Full chapter: [Error Handling](error-handling.md).

## Test without a server

Point settings at in-memory services:

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#test-settings -->
```kotlin
fun guideTest(action: suspend context(TestRunner<Server>) Server.() -> Unit) = Server.testBlocking(
    settings = {
        database set Database.Settings("ram")
        cache set Cache.Settings("ram")
        email set EmailService.Settings("test")
        files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
    },
    action = action,
)
```

Plain handler: `.test(pathArgs..., queryParameters, headers, body)` returns an `HttpResponse`:

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#test-plain -->
```kotlin
fun plainHandlerTest() = guideTest {
    val response = Routes.hello.test("Ada", queryParameters = QueryParameters.parse("shout=true"))
    check(response.status == HttpStatus.OK)
    check(response.body?.text() == "HELLO, ADA!")
}
```

Typed endpoint and errors:

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#test-typed -->
```kotlin
fun typedTest() = guideTest {
    // (path args..., auth, input) -> the typed output; null auth for noAuth endpoints.
    val quote = Quotes.byIndex.test(0, null, Unit)
    check(quote.author == "grug")

    // Thrown HttpStatusExceptions surface as Kotlin exceptions.
    val error = runCatching { Quotes.byIndex.test(99, null, Unit) }.exceptionOrNull() as HttpStatusException
    check(error.status == HttpStatus.NotFound && error.detail == "no-quote")

    // Through the HTTP pipeline instead, the same error is a 404 response.
    check(Quotes.byIndex.test(99).status.code == 404)
}
```

Authenticated calls with `testAuth`, database work in `execute { }`:

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#test-auth -->
```kotlin
fun authTest() = guideTest {
    // Runtime work done by the test itself (database, cache, tasks) goes inside execute { }.
    val admin = execute { Users.info.table().insertOne(User(email = "admin@example.com", role = Role.Admin))!! }
    val adminAuth = UserAuth.testAuth(admin)

    val post = Posts.rest.insert.test(adminAuth, Post(author = admin._id, title = "First"))
    check(Posts.rest.detail.test(post._id, adminAuth, Unit).title == "First")
    check(Account.postCount.test(adminAuth, Unit) == 1)

    val member = execute { Users.info.table().insertOne(User(email = "member@example.com"))!! }
    val denied = runCatching { Account.postCount.test(UserAuth.testAuth(member), Unit) }.exceptionOrNull()
    check(denied is ForbiddenException)
}
```

The full email login flow:

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#test-login -->
```kotlin
fun emailLoginTest() = guideTest {
    val key = Login.email.start.test(null, "new@example.com")

    val sent = (Server.email() as TestEmailService).lastEmailTo("new@example.com")!!
    val pin = sent.plainText.substringAfter("Your code is ")

    val proof = Login.email.prove.test(null, FinishProof(key, pin))
    val session = Login.sessions.login.test(null, listOf(proof))
    check(session.refreshToken != null)
}
```

Services, files and tasks:

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#test-services -->
```kotlin
fun servicesTest() = guideTest {
    val author = Uuid.random()
    check(execute { queryExamples(author) }.single().title == "Hello")

    execute { cacheExamples() }

    val url = Attachments.create.test(UserAuth.testAuth(User(email = "a@example.com")), "hi")
    check(url.isNotBlank())

    // Tasks run inline in tests, so their effects are visible on the next line.
    Jobs.signup.test(null, "new@example.com")
    check((Server.email() as TestEmailService).lastEmailTo("new@example.com")?.subject == "Welcome!")
}
```

WebSockets:

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#test-websocket -->
```kotlin
fun webSocketTest() = guideTest {
    val received = mutableListOf<String>()
    val alice = Chat.socket.test()
    val bob = Chat.socket.test()
    bob.onMessageSent = { received.add(it.text) }

    alice.send(WebSocketFrame("hi all"))
    check(received == listOf("hi all"))

    alice.close()
    bob.close()
}
```

Schedules:

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#test-schedule -->
```kotlin
// Runs the scheduled work the way the engine does when its timer fires.
@OptIn(EngineApi::class)
fun scheduleTest() = guideTest {
    Jobs.cleanup.executeWithMetrics()
}
```

Wrap each in a `@Test fun x() = plainHandlerTest()`-style method. Full chapter: [Testing](testing.md).

## Run locally

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#run-local -->
```kotlin
// settings.json is created with defaults on the first run if it is missing; edit it and rerun.
fun main() {
    KtorEngine(Server.build()).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        // Locally, run what a deploy would: table/index setup and other pre-deploy tasks.
        settings.ready()
        runPreDeployTasksBlocking()
        start(Netty)
    }
}
```

Point `settings.json` at real services by URL, for example `"database": {"url": "mongodb://localhost/app"}`
(reference `MongoDatabase` in `init` as with `DynamoDbCache` above). Never commit it.

Full chapter: [Running Your Server](running.md).

## Deploy to AWS

`settings()` maps each setting to infrastructure; `deploy()` generates Terraform and applies it:

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#aws -->
```kotlin
class AwsHandler : AwsAdapter(Server.build())

object Production : TerraformAwsServerlessDomainBuilder<Server>(Server) {
    override val handler: KClass<out AwsAdapter> = AwsHandler::class
    override val displayName = "Example API"
    override val region: Region = Region.US_WEST_2
    override val storageBucket = "example-terraform-state"
    override val domainZone = "example.com"
    override val domain = "api.example.com"
    override val emergencyContact = "ops@example.com".toEmailAddress()
    override val debug = false
    override val applicationVpc = AwsVpc.None

    // Each setting maps to infrastructure; Terraform is generated from this.
    override fun Server.settings() {
        database.byVariable()
        cache.awsDynamoDb()
        files.awsS3Bucket(signedUrlDuration = 1.days)
        email.byVariable()
        supportAddress.direct("support@example.com")
    }

    // Writes terraform/<project>/*.tf.json, then runs terraform init and apply.
    @JvmStatic
    fun main(vararg args: String) = deploy()
}
```

Build the Lambda bundle (the demo's `lambda` Sync task in `demo/build.gradle.kts`) before deploying.
`byVariable()` settings become Terraform variables you fill with `editVars()`.

Full chapter: [Deploying to AWS](aws-deployment.md).

## Add CORS and rate limiting

<!-- sample: com/lightningkite/lightningserver/guide/condensed/CondensedSamples.kt#cors-ratelimit -->
```kotlin
object PublicApi : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())
    val cors = setting("cors", CorsSettings(limitToDomains = listOf("https://app.example.com"), limitToMethods = listOf("*"), limitToHeaders = listOf("*")))
    val rateLimit = setting("rateLimit", RateLimitSettings())

    // CORS first, so browsers can read the rate limiter's 429s.
    val corsInterceptor = install(CorsInterceptor(cors))
    val rateLimiter = install(RateLimitInterceptor(rateLimit, cache) { request ->
        RequestLimits(key = "ip:${request.sourceIp}")
    })

    val ping = path.path("ping").get bind HttpHandler { HttpResponse.plainText("pong") }
}
```

Behind a load balancer `sourceIp` is the balancer's address; key on the user or a trusted forwarded header instead.
Exempt `meta.bulk` and multiplex sockets via `exempt` / `exemptSockets`, since their parts are charged individually.
Source: `ratelimit/` and `core/.../cors/`.

## Batch requests

`MetaEndpoints` (mounted at `/meta` in [Define the server](#define-the-server)) adds `POST /meta/bulk`:
a map of named sub-requests in, a map of named results out, always HTTP 200.

```json
{"a": {"path": "/quotes/0", "method": "GET"}, "b": {"path": "/account/me", "method": "GET"}}
```

Full chapter: [Bulk Endpoints](bulk-endpoints.md).

## Send email and notifications

`Server.email().send(Email(...))` from any handler or task (see `Jobs.welcome` above); `EmailService.Settings("test")`
records mail for assertions. SMS (`SMS.Settings`), push (`NotificationService.Settings`) and the `notifications`
module follow the same setting pattern: see `demo/.../Server.kt` and `notifications/`.
