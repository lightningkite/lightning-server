@file:Suppress("unused")

package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.files.UploadEarlyEndpoint
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.sessions.*
import com.lightningkite.lightningserver.sessions.proofs.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.cache.*
import com.lightningkite.services.cache.dynamodb.*
import com.lightningkite.services.cache.memcached.*
import com.lightningkite.services.database.*
import com.lightningkite.services.database.mongodb.*
import com.lightningkite.services.email.*
import com.lightningkite.services.files.*
import com.lightningkite.services.files.s3.*
import com.lightningkite.services.http.*
import com.lightningkite.services.metrics.cloudwatch.*
import com.lightningkite.services.sms.*
import io.ktor.client.request.*
import io.ktor.server.plugins.NotFoundException
import kotlinx.coroutines.*
import kotlinx.html.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlin.random.*
import kotlin.time.*
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.*

object Server : ServerBuilder() {

    val database = setting("database", Database.Settings())
    val email = setting("email", EmailService.Settings())
    val sms = setting("sms", SMS.Settings())
    val files = setting("files", PublicFileSystem.Settings())
    val cache = setting("cache", Cache.Settings())

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

        registerBasicMediaTypeCoders()
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
    val user = path.path("user") bind object : ServerBuilder() {
        val rest = path.path("rest") bind ModelRestEndpoints(userInfo)
    }
    val uploadEarly = path.path("upload") bind UploadEarlyEndpoint(files, database, Runtime.Constant(listOf()))
    val testModel = path.path("test-model") bind TestModelEndpoints()

    val root = path.get bind HttpHandler {
        HttpResponse.plainText("Hello ${it.auth(UserAuth.auth() or noAuth)?.fetch()}")
    }

    val socket = path.path("socket") bind WebSocketHandler(
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

    val task = path.path("Sample Task") bind Task { it: Int ->
        val id = Uuid.random()
        println("Got input $it in the sample task $id")
        var value = cache().get<Int>("key")
        println("From cache is $value for task $id")
        delay(1000L)
        value = cache().get<Int>("key")
        println("One second later, from cache is $value for task $id")
        println("Finishing sample task $id")
    }

    val runTask = path.path("run-task").get bind HttpHandler {
        val number = Random.nextInt(0, 100)
        task.invoke(number)
        HttpResponse.plainText("OK")
    }

    val testPrimitive = path.path("test-primitive").get bind ApiHttpHandler(
        auth = UserAuth.auth(),
        summary = "Get Test Primitive",
        errorCases = listOf(),
        implementation = { input: Unit -> "42 is great" }
    )
    val testObject = path.path("test-object").get bind ApiHttpHandler(
        auth = UserAuth.auth(),
        summary = "Get Test Object",
        errorCases = listOf(),
        examples = listOf(ApiHttpHandler.Example(input = Unit, output = TestModel())),
        implementation = { input: Unit ->
            TestModel()
        }
    )
    val die = path.path("die").get bind HttpHandler {  throw Exception("OUCH") }

    val fileSignPerfCheck = path.path("file-sign-perf-check").get bind HttpHandler {
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

    val databaseCheck = path.path("database-check").get bind HttpHandler {
        HttpResponse.plainText(database().collection<User>()::class.qualifiedName ?: "???")
    }

    val testSchedule = path.path("test-schedule") bind ScheduledTask(frequency = 5.minutes) {
        println("Hello schedule!")
    }

    val hasInternet = path.path("has-internet").get bind HttpHandler {
        println("Checking for internet...")
        val response = client.get("https://lightningkite.com")
        HttpResponse.plainText("Got status ${response.status}")
    }

    val dieSlowly = path.path("die-slowly").get bind HttpHandler {
        Thread.sleep(60_000L)
        HttpResponse.plainText("Impossible.")
    }

    val multiplex = path.path("multiplex").websocket(MultiplexWebSocketHandler())

    val meta = path.path("meta") bind MetaEndpoints("com.lightningkite.lightningserver.demo", database, cache)

    val pins = PinHandler(cache, "pins")
    val proofPhone = path.path("proof").path("phone") bind SmsProofEndpoints(pins, sms)
    val proofEmail = path.path("proof").path("email") bind EmailProofEndpoints(pins, email, { to, pin ->
        Email(
            subject = "Log In Code",
            to = listOf(EmailAddressWithName(to)),
            plainText = "Your PIN is $pin."
        )
    })
    val proofOtp = path.path("proof").path("otp") bind TimeBasedOTPProofEndpoints(database, cache)
    val proofPassword = path.path("proof").path("password") bind PasswordProofEndpoints(database, cache)
    val proofDevices = path.path("proof").path("devices") bind KnownDeviceProofEndpoints(database, cache)
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
    val causeOutOfMemory = path.path("cause-out-of-memory").get bind HttpHandler {
        while (true) {
            permanentMemory += ByteArray(1024 * 1024)
            println("Allocated ${permanentMemory.size} times")
        }
        HttpResponse.plainText("This should not be reachable.")
    }

    val sample = path.path("page").get bind HttpHandler {
        HttpResponse.html {

            head {

            }

            body {
                div {
                    p { +"My text here" }
                }
                input(InputType.email) {
                    id = "asdf"
                }
            }

        }
    }

    val memLeakCheck = path.path("memLeakCheck").post bind HttpHandler {
        // Not reading the body.  Is that it?
//        HttpResponse.plainText("OK")
//        HttpResponse.json(testModel.info.collection().all().toList())
        val auth = it.auth(UserAuth.auth())
        HttpResponse.plainText(auth.id.toString())
    }
}
