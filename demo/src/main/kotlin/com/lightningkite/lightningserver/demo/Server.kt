package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.Task
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import java.lang.IllegalStateException
import kotlin.time.Duration
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.builder.bind
import com.lightningkite.lightningserver.definition.builder.setting
import com.lightningkite.lightningserver.html
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.path
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.sessions.AuthEndpoints
import com.lightningkite.lightningserver.sessions.proofs.EmailProofEndpoints
import com.lightningkite.lightningserver.sessions.proofs.KnownDeviceProofEndpoints
import com.lightningkite.lightningserver.sessions.proofs.PasswordProofEndpoints
import com.lightningkite.lightningserver.sessions.proofs.PinHandler
import com.lightningkite.lightningserver.sessions.proofs.SmsProofEndpoints
import com.lightningkite.lightningserver.sessions.proofs.TimeBasedOTPProofEndpoints
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.lightningserver.typed.auth
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.lightningserver.websockets.MultiplexWebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.send
import com.lightningkite.lightningserver.websockets.text
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.dynamodb.DynamoDbCache
import com.lightningkite.services.cache.get
import com.lightningkite.services.cache.memcached.MemcachedCache
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.ModelPermissions
import com.lightningkite.services.database.collection
import com.lightningkite.services.database.condition
import com.lightningkite.services.database.get
import com.lightningkite.services.database.insertOne
import com.lightningkite.services.database.mongodb.MongoDatabase
import com.lightningkite.services.database.or
import com.lightningkite.services.email.Email
import com.lightningkite.services.email.EmailAddressWithName
import com.lightningkite.services.email.EmailService
import com.lightningkite.services.files.PublicFileSystem
import com.lightningkite.services.files.s3.S3PublicFileSystem
import com.lightningkite.services.http.client
import com.lightningkite.services.metrics.cloudwatch.CloudwatchMetricReporter
import com.lightningkite.services.sms.SMS
import io.ktor.client.request.get
import io.ktor.server.plugins.NotFoundException
import kotlinx.coroutines.flow.toList
import kotlinx.html.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

object Server : ServerBuilder() {

    val database = setting("database", Database.Settings())
    val email = setting("email", EmailService.Settings())
    val sms = setting("sms", SMS.Settings())
    val files = setting("files", PublicFileSystem.Settings())
    val cache = setting("cache", Cache.Settings())

    private inline fun startupOnce(key: String, database: Runtime<Database>, noinline action: suspend ServerRuntime.() -> Unit) {
        path.path(key) bind StartupTask {
            doOnce(key, database, action = action)
        }
    }

    init {
        CloudwatchMetricReporter
        DynamoDbCache
        MongoDatabase
        MemcachedCache
//        SentryExceptionReporter
        S3PublicFileSystem
        startupOnce("adminUser", database) {
            database().collection<User>().insertOne(
                User(
                    email = "joseph+admin@lightningkite.com",
                    isSuperUser = true
                )
            )
        }
    }

    object UserAuth: PrincipalType<User, Uuid> {
        override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
        override val subjectSerializer: KSerializer<User> = User.Companion.serializer()

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): User = userInfo.collection().get(id) ?: throw NotFoundException()
    }

    val userInfo = database.modelInfo<User, User, Uuid>(
        auth = UserAuth.auth(),
        permissions = {
            val user = auth.fetch()
            val everyone: Condition<User> = Condition.Always
            val self: Condition<User> = condition { it._id eq user._id }
            val admin: Condition<User> = if (user.isSuperUser) Condition.Always else Condition.Never
            ModelPermissions(
                create = everyone,
                read = self or admin,
                update = self or admin,
                delete = self or admin
            )
        }
    )
    val user = path("user") bind object: ServerBuilder() {
        val rest = path("rest") bind ModelRestEndpoints(userInfo)
    }

    val uploadEarly = path("upload") bind object: ServerBuilder() { init { TODO() } }
//    val uploadEarly = UploadEarlyEndpoint(path("upload"), files, database)
    val testModel = path("test-model") bind TestModelEndpoints()

    val root = path.get bind HttpHandler {
        HttpResponse.plainText("Hello ${it.auth(UserAuth.auth() or noAuth)}")
    }

    val socket = path("socket") bind WebSocketHandler(
        willConnect = { Uuid.random().toString() },
        didConnect = { /*send("Connected $currentState")*/ },
        messageFromClient = {
            send(it.text)
            if (it.content == "die") {
                throw Exception("You asked me to die!")
            }
        },
        disconnect = { println("Disconnect $currentState") }
    )

    val task = path("Sample Task") bind Task { it: Int ->
        val id = Uuid.random()
        println("Got input $it in the sample task $id")
        var value = cache().get<Int>("key")
        println("From cache is $value for task $id")
        delay(1000L)
        value = cache().get<Int>("key")
        println("One second later, from cache is $value for task $id")
        println("Finishing sample task $id")
    }

    val runTask = path("run-task").get bind HttpHandler {
        val number = Random.nextInt(0, 100)
        task.invoke(number)
        HttpResponse.plainText("OK")
    }

    val testPrimitive = path("test-primitive").get bind ApiHttpHandler(
        auth = UserAuth.auth(),
        summary = "Get Test Primitive",
        errorCases = listOf(),
        implementation = { input: Unit -> "42 is great" }
    )
    val testObject = path("test-object").get bind ApiHttpHandler(
        auth = UserAuth.auth(),
        summary = "Get Test Object",
        errorCases = listOf(),
        examples = listOf(ApiHttpHandler.Example(input = Unit, output = TestModel())),
        implementation = { input: Unit ->
            TestModel()
        }
    )
    val die = path("die").get bind HttpHandler {  throw Exception("OUCH") }

    val fileSignPerfCheck = path("file-sign-perf-check").get bind HttpHandler {
        val system = files()
        var endAt = System.currentTimeMillis() + 1000
        while(System.currentTimeMillis() < endAt)
            system.root.resolve("test.txt").signedUrl
        var count = 0
        endAt = System.currentTimeMillis() + 1000
        var last = ""
        while(System.currentTimeMillis() < endAt) {
            count++
            last = system.root.resolve("test.txt").signedUrl
        }
        HttpResponse.plainText("$count - $last")
    }

    val databaseCheck = path("database-check").get bind HttpHandler {
        HttpResponse.plainText(database().collection<User>()::class.qualifiedName ?: "???")
    }

    val testSchedule = schedule("test-schedule", 5.minutes) {
        println("Hello schedule!")
    }

    val hasInternet = path("has-internet").get bind HttpHandler {
        println("Checking for internet...")
        val response = client.get("https://lightningkite.com")
        HttpResponse.plainText("Got status ${response.status}")
    }

    val dieSlowly = path("die-slowly").get bind HttpHandler {
        Thread.sleep(60_000L)
        HttpResponse.plainText("Impossible.")
    }

    val multiplex = path("multiplex").websocket(MultiplexWebSocketHandler())

    val meta = path("meta").metaEndpoints()

    val pins = PinHandler(cache, "pins")
    val proofPhone = path("proof/phone") bind SmsProofEndpoints(pins, sms)
    val proofEmail = path("proof/email") bind EmailProofEndpoints(pins, email, { to, pin ->
        Email(
            subject = "Log In Code",
            to = listOf(EmailAddressWithName(to)),
            plainText = "Your PIN is $pin."
        )
    })
    val proofOtp = path("proof/otp") bind TimeBasedOTPProofEndpoints(database, cache)
    val proofPassword = path("proof/password") bind PasswordProofEndpoints(database, cache)
    val proofDevices = path("proof/devices") bind KnownDeviceProofEndpoints(database, cache)
    val subjects = object: AuthEndpoints<User, Uuid>(
        principal = UserAuth,
        database = database,
    ) {
        context(server: ServerRuntime)
        override suspend fun requiredProofStrengthFor(subject: User): Int = 5

        context(server: ServerRuntime)
        override suspend fun sessionExpiration(subject: User): Instant? = null

        context(server: ServerRuntime)
        override suspend fun sessionStaleAfter(subject: User): Duration? = null
    }

    val permanentMemory = ArrayList<ByteArray>()
    val causeOutOfMemory = get("cause-out-of-memory") bind HttpHandler {
        while (true) {
            permanentMemory += ByteArray(1024 * 1024)
            println("Allocated ${permanentMemory.size} times")
        }
        HttpResponse.plainText("This should not be reachable.")
    }

    val sample = get("page") bind HttpHandler {
        HttpResponse.html {

            head {

            }

            body {
                div {
                    p { +"My text here"  }
                }
                input(InputType.email) {
                    id = "asdf"
                }
            }

        }
    }

    val memLeakCheck = post("memLeakCheck") bind HttpHandler {
        // Not reading the body.  Is that it?
//        HttpResponse.plainText("OK")
//        HttpResponse.json(testModel.info.collection().all().toList())
        val auth = it.auth(UserAuth.auth())
        HttpResponse.plainText(auth.id.toString())
    }
}
