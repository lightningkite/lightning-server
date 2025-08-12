package com.lightningkite.lightningserverdemo

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.builder.bind
import com.lightningkite.lightningserver.definition.builder.setting
import com.lightningkite.lightningserver.definition.builder.topic
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.send
import com.lightningkite.lightningserver.websockets.WebSocketClose
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.subscribe
import com.lightningkite.lightningserver.websockets.text
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import java.lang.IllegalStateException
import kotlin.time.Duration
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.html.*



object Server : ServerBuilder() {

    val index = path.get bind HttpHandler {
        HttpResponse.plainText("Ktor Test Success")
    }
    val topic = path.topic(String.serializer())

    val websocket = path bind WebSocketHandler(
        storageSerializer = Unit.serializer(),
        willConnect = { Unit },
        didConnect = { com.lightningkite.lightningserver.websockets.subscribe(topic) },
        topicHandlers = {
            topic bind {
                println("Topic hit!")
                send(WebSocketFrame(it.value))
            }
        },
        messageFromClient = { frame ->
            if (frame is WebSocketFrame.Text && frame.text == "close") {
                close(WebSocketClose.NORMAL)
            } else {
                send(frame)  // Mirror
            }
        },
        disconnect = {}
    )

    val ping = path.path("ping").post bind HttpHandler {
        val body = it.body?.text()
        println(body)
        topic.send(body ?: "No Body")
        HttpResponse()
    }

    val module = path.path("module") bind Module

//    val database = setting("database", DatabaseSettings())
//    val email = setting("email", EmailSettings())
//    val jwtSigner = setting("jwt", JwtSigner())
//    val sms = setting("sms", SMSSettings())
//    val files = setting("files", FilesSettings())
//    val cache = setting("cache", CacheSettings())

//    init {
//        Metrics
//        CloudwatchMetrics
//        DynamoDbCache
//        MongoDatabase
//        MemcachedCache
//        SentryExceptionReporter
//        S3FileSystem
//        prepareModelsShared()
//        prepareModelsServerCore()
//        prepareModelsDemo()
//        Tasks.onSettingsReady {
//            Metrics.main()
//            println("Files started, got ${files().root.url}")
//        }
//        Serialization.handler(FileRedirectHandler)
//        startupOnce("adminUser", database) {
//            database().collection<User>().insertOne(
//                User(
//                    email = "joseph+admin@lightningkite.com",
//                    isSuperUser = true
//                )
//            )
//        }
//        Authentication.isDeveloper = authRequired<User> {
//            (it.get() as User).isSuperUser
//        }
//        Authentication.isSuperUser = authRequired<User> {
//            (it.get() as User).isSuperUser
//        }
//    }

//    val userInfo = database.modelInfo<User, User, UUID>(
//        authOptions = authOptions(),
//        serialization = ModelSerializationInfo(),
//        permissions = {
//            val user = user()
//            val everyone: Condition<User> = Condition.Always
//            val self: Condition<User> = condition { it._id eq user._id }
//            val admin: Condition<User> = if (user.isSuperUser) Condition.Always else Condition.Never
//            ModelPermissions(
//                create = everyone,
//                read = self or admin,
//                update = self or admin,
//                delete = self or admin
//            )
//        }
//    )
//    val user = object : ServerPathGroup(path("user")) {
//        val rest = ModelRestEndpoints(path("rest"), userInfo)
//    }
//
//    val uploadEarly = UploadEarlyEndpoint(path("upload"), files, database)
//    val testModel = TestModelEndpoints(path("test-model"))
//
//    val root = path.get.handler {
//        HttpResponse.plainText("Hello ${it.user<User?>()}")
//    }
//
//    val die2 = path("oom").get.handler {
//        throw OutOfMemoryError("Fake")
//    }
//
//    val socket = path("socket").websocket(
//        willConnect = { UUID.random().toString() },
//        didConnect = { /*send("Connected $currentState")*/ },
//        message = {
//            send(it.text)
//            if (it.content == "die") {
//                throw Exception("You asked me to die!")
//            }
//        },
//        disconnect = { println("Disconnect $currentState") }
//    )
//
//    val task = task("Sample Task") { it: Int ->
//        val id = UUID.random()
//        println("Got input $it in the sample task $id")
//        var value = cache().get<Int>("key")
//        println("From cache is $value for task $id")
//        delay(1000L)
//        value = cache().get<Int>("key")
//        println("One second later, from cache is $value for task $id")
//        println("Finishing sample task $id")
//    }
//
//    val runTask = path("run-task").get.handler {
//        val number = Random.nextInt(0, 100)
//        task(number)
//        HttpResponse.plainText("OK")
//    }
//
//    val testPrimitive = path("test-primitive").get.typed(
//        summary = "Get Test Primitive",
//        errorCases = listOf(),
//        implementation = { user: User?, input: Unit -> "42 is great" }
//    )
//    val testObject = path("test-object").get.typed(
//        summary = "Get Test Object",
//        errorCases = listOf(),
//        examples = listOf(ApiExample(input = Unit, output = TestModel())),
//        implementation = { user: User?, input: Unit ->
//            TestModel()
//        }
//    )
//    val die = path("die").get.handler { throw Exception("OUCH") }
//
//    val fileSignPerfCheck = path("file-sign-perf-check").get.handler {
//        val system = files()
//        var endAt = System.currentTimeMillis() + 1000
//        while(System.currentTimeMillis() < endAt)
//            system.root.resolve("test.txt").signedUrl
//        var count = 0
//        endAt = System.currentTimeMillis() + 1000
//        var last = ""
//        while(System.currentTimeMillis() < endAt) {
//            count++
//            last = system.root.resolve("test.txt").signedUrl
//        }
//        HttpResponse.plainText(count.toString() + " - $last")
//    }
//
//    val databaseCheck = path("database-check").get.handler {
//        HttpResponse.plainText(database().collection<User>()::class.qualifiedName ?: "???")
//    }
//
//    val testSchedule = schedule("test-schedule", 5.minutes) {
//        println("Hello schedule!")
//    }
//
//    val hasInternet = path("has-internet").get.handler {
//        println("Checking for internet...")
//        val response = client.get("https://lightningkite.com")
//        HttpResponse.plainText("Got status ${response.status}")
//    }
//
//    val dieSlowly = path("die-slowly").get.handler {
//        Thread.sleep(60_000L)
//        HttpResponse.plainText("Impossible.")
//    }
//
//    val multiplex = path("multiplex").websocket(MultiplexWebSocketHandler(cache))
//
//    val meta = path("meta").metaEndpoints()
//
//    val weirdAuth = path("weird-auth").get.typed(
//        summary = "Get weird auth",
//        errorCases = listOf(),
//        implementation = { user: RequestAuth<User>, _: Unit ->
//            "ID is ${user.id}"
//        }
//    )
//
//    val pins = PinHandler(cache, "pins")
//    val proofPhone = SmsProofEndpoints(path("proof/phone"), pins, sms)
//    val proofEmail = EmailProofEndpoints(path("proof/email"), pins, email, { to, pin ->
//        Email(
//            subject = "Log In Code",
//            to = listOf(EmailLabeledValue(to)),
//            plainText = "Your PIN is $pin."
//        )
//    })
//    val proofOtp = OneTimePasswordProofEndpoints(path("proof/otp"), database, cache)
//    val proofPassword = PasswordProofEndpoints(path("proof/password"), database, cache)
//    val proofDevices = KnownDeviceProofEndpoints(path("proof/devices"), database, cache)
//    val subjects = AuthEndpointsForSubject(
//        path("subject"),
//        object : Authentication.SubjectHandler<User, UUID> {
//            override val name: String get() = "User"
//            override val authType: AuthType get() = AuthType<User>()
//            override val idSerializer: KSerializer<UUID>
//                get() = userInfo.serialization.idSerializer
//            override val subjectSerializer: KSerializer<User>
//                get() = userInfo.serialization.serializer
//
//            override suspend fun fetch(id: UUID): User = userInfo.collection().get(id) ?: throw NotFoundException()
//            override suspend fun findUser(property: String, value: String): User? = when (property) {
//                "email" -> userInfo.collection().findOne(condition { it.email eq value })
//                "_id" -> userInfo.collection().get(UUID.parse(value))
//                else -> null
//            }
//
//            override val knownCacheTypes: List<RequestAuth.CacheKey<User, UUID, *>> = listOf(EmailCacheKey)
//
//            override suspend fun desiredStrengthFor(result: User): Int = if (result.isSuperUser) Int.MAX_VALUE else 5
//        },
//        database = database
//    )
//
//    val sample = get("page").handler {
//        HttpResponse.html {
//
//            head {
//
//            }
//
//            body {
//                div {
//                    p { +"My text here"  }
//                }
//                input(InputType.email) {
//                    id = "asdf"
//                }
//            }
//
//        }
//    }
}

object Module : ServerBuilder() {
    val index = path.get bind HttpHandler {
        HttpResponse.plainText("Module hit")
    }

    val topic = path.topic(String.serializer())

    val websocket = path bind WebSocketHandler(
        storageSerializer = Unit.serializer(),
        willConnect = { Unit },
        didConnect = { subscribe(topic); subscribe(Server.topic) },
        topicHandlers = {
            topic bind {
                println("Module Topic hit!")
                send(WebSocketFrame("Module" + it.value))
            }
            Server.topic bind {
                println("Server Topic hit!")
                send(WebSocketFrame("Server" + it.value))
            }
        },
        messageFromClient = { frame ->
            if (frame is WebSocketFrame.Text && frame.text == "close") {
                close(WebSocketClose.NORMAL)
            } else {
                send(frame)  // Mirror
            }
        },
        disconnect = {}
    )
}

//object EmailCacheKey : RequestAuth.CacheKey<User, UUID, String>() {
//    override val name: String
//        get() = "email"
//    override val serializer: KSerializer<String>
//        get() = String.serializer()
//    override val validFor: Duration
//        get() = 5.minutes
//
//    override suspend fun calculate(auth: RequestAuth<User>): String = auth.get().email
//}