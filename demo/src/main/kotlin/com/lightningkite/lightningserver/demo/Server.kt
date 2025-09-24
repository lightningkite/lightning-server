@file:Suppress("unused")

package com.lightningkite.lightningserver.demo

import com.lightningkite.DataSize.Companion.bytes
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.cors.CorsInterceptor
import com.lightningkite.lightningserver.cors.CorsSettings
import com.lightningkite.lightningserver.data.alter
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.files.UploadEarlyEndpoint
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.first
import com.lightningkite.lightningserver.pathing.second
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.sessions.*
import com.lightningkite.lightningserver.sessions.proofs.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.cache.*
import com.lightningkite.services.cache.dynamodb.*
import com.lightningkite.services.cache.memcached.*
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.*
import com.lightningkite.services.database.jsonfile.JsonFileDatabase
import com.lightningkite.services.database.mongodb.*
import com.lightningkite.services.email.*
import com.lightningkite.services.email.javasmtp.JavaSmtpEmailService
import com.lightningkite.services.files.*
import com.lightningkite.services.files.s3.*
import com.lightningkite.services.http.*
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
    val cors = setting("cors", CorsSettings())

    val corsInterceptor = install(CorsInterceptor(cors))

    init {
        JavaSmtpEmailService
        JsonFileDatabase
        DynamoDbCache
        MongoDatabase
        MemcachedCache
        S3PublicFileSystem
    }

    val setupAdmins = path.path("setup-admins") bind startupOnce(database) {
        userInfo.table().insertOne(User(email = "joseph+root@lightningkite.com", isSuperUser = true))
    }

    object UserAuth: PrincipalType<User, Uuid> {
        override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
        override val subjectSerializer: KSerializer<User> = User.Companion.serializer()
        override val name: String get() = User.serializer().descriptor.serialName.substringAfterLast('.')

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): User = userInfo.table().get(id) ?: throw NotFoundException()

        context(server: ServerRuntime)
        override suspend fun fetchByProperty(property: String, value: String): User? {
            return when(property) {
                "email" -> return userInfo.table().findOne(condition { it.email eq value }) ?: userInfo.table().insertOne(User(email = value))
                else -> super.fetchByProperty(property, value)
            }
        }
    }

    val userInfo: ModelInfo<User?, User, Uuid> = database.modelInfo(
        auth = UserAuth.require() or AuthRequirement.NotAuthenticated,
        permissions = {
            val user = authOrNull?.fetch()
            val everyone: Condition<User> = Condition.Always
            val self: Condition<User> = condition { it._id eqNn user?._id }
            val admin: Condition<User> = if (user?.isSuperUser == true) Condition.Always else Condition.Never
            ModelPermissions(
                create = Condition.Never,
                read = everyone,
//                read = self or admin,
                update = self or admin,
                delete = self or admin
            )
        }
    )

    val user = path.path("user") include object : ServerBuilder() {
        val rest = path.path("rest") module ModelRestEndpoints(userInfo)
    }
    val uploadEarly = path.path("upload") module UploadEarlyEndpoint(files, database, Runtime.Constant(listOf()))
    val testModel = path.path("test-model") module TestModelEndpoints

    val root = path.get bind HttpHandler {
        HttpResponse.plainText("Hello ${it.auth(UserAuth.require() or noAuth)?.fetch()}")
    }

    val slashEscaping = path.path("variable").arg<String>("stupidid").get bind HttpHandler { request ->
        HttpResponse.plainText("The variable is '${request.path.first}'")
    }

    val topic = path.path("socket-topic").topic(String.serializer())
    val socketSideMessage = path.path("socket").arg<String>("tosend").get bind HttpHandler {
        topic.send(it.first)
        HttpResponse.plainText("Sent!")
    }
    val socket = path.path("socket") bind WebSocketHandler(
        willConnect = { Uuid.random().toString() },
        didConnect = {
            println("didConnect $currentState")
            subscribe(topic)
        },
        messageFromClient = {
            println("messageFromClient $currentState $it")
            send(it.text)
            if (it.content == "die") {
                throw Exception("You asked me to die!")
            }
        },
        topicHandlers = {
            topic bind {
                println("topicHandlers $currentState $it")
                send(it.value)
            }
        },
        disconnect = { println("Disconnect $currentState") }
    )

    val mockWorkGet = path.path("mock-work").get bind HttpHandler {
        repeat(5) {
            // Mocking DB requests
            delay(10)
        }
        HttpResponse.plainText("ok")
    }
    val mockWorkPost = path.path("mock-work").post bind HttpHandler {
        val bytes = it.body?.data?.bytes()
        repeat(5) {
            // Mocking DB requests
            delay(10)
        }
        HttpResponse(body = bytes?.let { bytes -> TypedData.bytes(bytes, it.body!!.mediaType) })
    }

    val mem = path.path("mem").get bind HttpHandler {
        repeat(5) { System.gc() }
        val max = java.lang.Runtime.getRuntime().maxMemory().bytes
        val total = java.lang.Runtime.getRuntime().totalMemory().bytes
        val free = java.lang.Runtime.getRuntime().freeMemory().bytes
        val memory = ServerHealth.Memory(
            max = max,
            total = total,
            free = free,
            systemAllocated = total - free,
            usage = ((total - free).bytes.toDouble() / max.bytes.toDouble()).toFloat()
        )
        HttpResponse.plainText("Memory usage: ${memory}")
    }

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
        auth = UserAuth.require(),
        summary = "Get Test Primitive",
        errorCases = listOf(),
        implementation = { input: Unit -> "42 is great" }
    )
    val testObject = path.path("test-object").get bind ApiHttpHandler(
        auth = UserAuth.require(),
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
            system.root.then("test.txt").signedUrl
        var count = 0
        endAt = System.currentTimeMillis() + 1000
        var last = ""
        while(System.currentTimeMillis() < endAt) {
            count++
            last = system.root.then("test.txt").signedUrl
        }
        HttpResponse.plainText("$count - $last")
    }

    val databaseCheck = path.path("database-check").get bind HttpHandler {
        HttpResponse.plainText(database().table<User>()::class.qualifiedName ?: "???")
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

    val meta = path.path("meta") module MetaEndpoints("com.lightningkite.lightningserver.demo", database, cache)

    val pins = PinHandler(cache, "pins")
    val proofPhone = path.path("proof").path("phone") module SmsProofEndpoints(pins, sms)
    val proofEmail = path.path("proof").path("email") module EmailProofEndpoints(pins, email, { to, pin ->
        Email(
            subject = "Log In Code",
            to = listOf(EmailAddressWithName(to)),
            plainText = "Your PIN is $pin."
        )
    })
    val proofOtp = path.path("proof").path("otp") module TimeBasedOTPProofEndpoints(database, cache)
    val proofPassword = path.path("proof").path("password") module PasswordProofEndpoints(database, cache)
    val proofDevices = path.path("proof").path("devices") module KnownDeviceProofEndpoints(database, cache)
    val subjects = path.path("auth") module object: AuthEndpoints<User, Uuid>(
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

    val arged = path.path("arged").arg<String>("name").arg<Int>("arg2").get bind ApiHttpHandler(
        auth = UserAuth.require(),
        summary = "Arged",
        implementation = { input: Unit ->

        }
    )

    val arged2 = path.path("arged2").arg<String>("name").arg<Int>("arg2").get bind ApiHttpHandler(
        auth = UserAuth.require(),
        summary = "Arged",
        implementation = { input: Unit ->
            arged(input)
        }
    )

    val indirect = path.path("indirect").arg<String>("id").arg<Int>("arg2").post bind HttpHandler { request ->
        arged(request, request.first, request.second, Unit)

        HttpResponse()
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
        val auth = it.auth(UserAuth.require())
        HttpResponse.plainText(auth.id.toString())
    }

    init {
        registerBasicMediaTypeCoders()
    }
}
