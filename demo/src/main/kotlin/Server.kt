package com.lightningkite.lightningserverdemo

import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.auth.old.*
import com.lightningkite.lightningserver.auth.old.BaseAuthEndpoints
import com.lightningkite.lightningserver.auth.old.EmailAuthEndpoints
import com.lightningkite.lightningserver.auth.old.PasswordAuthEndpoints
import com.lightningkite.lightningserver.auth.proof.*
import com.lightningkite.lightningserver.auth.proof.PinHandler
import com.lightningkite.lightningserver.auth.subject.AuthEndpointsForSubject
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.cache.MemcachedCache
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.*
import com.lightningkite.lightningserver.email.Email
import com.lightningkite.lightningserver.email.EmailLabeledValue
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.encryption.secureHash
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.exceptions.SentryExceptionReporter
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.files.S3FileSystem
import com.lightningkite.lightningserver.files.UploadEarlyEndpoint
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.handler
import com.lightningkite.lightningserver.meta.metaEndpoints
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.serialization.FileRedirectHandler
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.setting
import com.lightningkite.lightningserver.sms.SMSSettings
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.tasks.startupOnce
import com.lightningkite.lightningserver.tasks.task
import com.lightningkite.lightningserver.typed.ApiExample
import com.lightningkite.lightningserver.typed.typed
import com.lightningkite.lightningserver.websocket.MultiplexWebSocketHandler
import com.lightningkite.lightningserver.websocket.websocket
import io.ktor.client.request.*
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import java.lang.IllegalStateException
import kotlin.time.Duration
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import com.lightningkite.UUID
import com.lightningkite.prepareModelsServerCore
import com.lightningkite.lightningserver.files.S3File
import com.lightningkite.prepareModelsShared
import com.lightningkite.uuid
import kotlinx.html.*

object Server : ServerPathGroup(ServerPath.root) {

    val database = setting("database", DatabaseSettings())
    val email = setting("email", EmailSettings())
    val jwtSigner = setting("jwt", JwtSigner())
    val sms = setting("sms", SMSSettings())
    val files = setting("files", FilesSettings())
    val cache = setting("cache", CacheSettings())

    init {
        Metrics
        DynamoDbCache
        MongoDatabase
        MemcachedCache
        SentryExceptionReporter
        S3FileSystem
        prepareModelsShared()
        prepareModelsServerCore()
        prepareModelsDemo()
        Tasks.onSettingsReady {
            Metrics.main()
            println("Files started, got ${files().root.url}")
        }
        Serialization.handler(FileRedirectHandler)
        startupOnce("adminUser", database) {
            database().collection<User>().insertOne(
                User(
                    email = "joseph+admin@lightningkite.com",
                    isSuperUser = true
                )
            )
        }
        Authentication.isDeveloper = authRequired<User> {
            (it.get() as User).isSuperUser
        }
        Authentication.isSuperUser = authRequired<User> {
            (it.get() as User).isSuperUser
        }
    }

    val userInfo = modelInfoWithDefault<User, User, UUID>(
        authOptions = authOptions(),
        serialization = ModelSerializationInfo(),
        getBaseCollection = { database().collection() },
        defaultItem = { User(email = "") },
        forUser = { it ->
            val user = user()
            val everyone: Condition<User> = Condition.Always()
            val self: Condition<User> = condition { it._id eq user._id }
            val admin: Condition<User> = if (user.isSuperUser) Condition.Always() else Condition.Never()
            it.withPermissions(
                ModelPermissions(
                    create = everyone,
                    read = self or admin,
                    update = self or admin,
                    delete = self or admin
                )
            )
        }
    )
    val user = object : ServerPathGroup(path("user")) {
        val rest = ModelRestEndpoints(path("rest"), userInfo)
    }

    val uploadEarly = UploadEarlyEndpoint(path("upload"), files, database)
    val testModel = TestModelEndpoints(path("test-model"))

    val root = path.get.handler {
        HttpResponse.plainText("Hello ${it.user<User?>()}")
    }

    val socket = path("socket").websocket(
        connect = { println("Connected $it - you are ${it.user<User?>()}") },
        message = {
            println("Message $it")
            it.id.send(it.content)
            if (it.content == "die") {
                throw Exception("You asked me to die!")
            }
        },
        disconnect = { println("Disconnect $it") }
    )

    val task = task("Sample Task") { it: Int ->
        val id = uuid()
        println("Got input $it in the sample task $id")
        var value = cache().get<Int>("key")
        println("From cache is $value for task $id")
        delay(1000L)
        value = cache().get<Int>("key")
        println("One second later, from cache is $value for task $id")
        println("Finishing sample task $id")
    }

    val runTask = path("run-task").get.handler {
        val number = Random.nextInt(0, 100)
        task(number)
        HttpResponse.plainText("OK")
    }

    val testPrimitive = path("test-primitive").get.typed(
        summary = "Get Test Primitive",
        errorCases = listOf(),
        implementation = { user: User?, input: Unit -> "42 is great" }
    )
    val testObject = path("test-object").get.typed(
        summary = "Get Test Object",
        errorCases = listOf(),
        examples = listOf(ApiExample(input = Unit, output = TestModel())),
        implementation = { user: User?, input: Unit ->
            TestModel()
        }
    )
    val die = path("die").get.handler { throw Exception("OUCH") }

    val fileSignPerfCheck = path("file-sign-perf-check").get.handler {
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
        HttpResponse.plainText(count.toString() + " - $last")
    }

    val databaseCheck = path("database-check").get.handler {
        HttpResponse.plainText(database().collection<User>()::class.qualifiedName ?: "???")
    }

    val testSchedule = schedule("test-schedule", 1.minutes) {
        println("Hello schedule!")
    }
    val testSchedule2 = schedule("test-schedule2", 1.minutes) {
        println("Hello schedule 2!")
    }

    val hasInternet = path("has-internet").get.handler {
        println("Checking for internet...")
        val response = client.get("https://lightningkite.com")
        HttpResponse.plainText("Got status ${response.status}")
    }

    val dieSlowly = path("die-slowly").get.handler {
        Thread.sleep(60_000L)
        HttpResponse.plainText("Impossible.")
    }

    val multiplex = path("multiplex").websocket(MultiplexWebSocketHandler(cache))

    val meta = path("meta").metaEndpoints()

    val weirdAuth = path("weird-auth").get.typed(
        summary = "Get weird auth",
        errorCases = listOf(),
        implementation = { user: RequestAuth<User>, _: Unit ->
            "ID is ${user.id}"
        }
    )

    val pins = PinHandler(cache, "pins")
    val proofPhone = SmsProofEndpoints(path("proof/phone"), pins, sms)
    val proofEmail = EmailProofEndpoints(path("proof/email"), pins, email, { to, pin ->
        Email(
            subject = "Log In Code",
            to = listOf(EmailLabeledValue(to)),
            plainText = "Your PIN is $pin."
        )
    })
    val proofOtp = OneTimePasswordProofEndpoints(path("proof/otp"), database, cache)
    val proofPassword = PasswordProofEndpoints(path("proof/password"), database, cache)
    val proofDevices = KnownDeviceProofEndpoints(path("proof/devices"), database, cache)
    val subjects = AuthEndpointsForSubject(
        path("subject"),
        object : Authentication.SubjectHandler<User, UUID> {
            override val name: String get() = "User"
            override val authType: AuthType get() = AuthType<User>()
            override val idSerializer: KSerializer<UUID>
                get() = userInfo.serialization.idSerializer
            override val subjectSerializer: KSerializer<User>
                get() = userInfo.serialization.serializer

            override suspend fun fetch(id: UUID): User = userInfo.collection().get(id) ?: throw NotFoundException()
            override suspend fun findUser(property: String, value: String): User? = when (property) {
                "email" -> userInfo.collection().findOne(condition { it.email eq value })
                "_id" -> userInfo.collection().get(uuid(value))
                else -> null
            }

            override val knownCacheTypes: List<RequestAuth.CacheKey<User, UUID, *>> = listOf(EmailCacheKey)

            override suspend fun desiredStrengthFor(result: User): Int = if (result.isSuperUser) Int.MAX_VALUE else 5
        },
        database = database
    )

    val sample = get("page").handler {
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
}

object EmailCacheKey : RequestAuth.CacheKey<User, UUID, String>() {
    override val name: String
        get() = "email"
    override val serializer: KSerializer<String>
        get() = String.serializer()
    override val validFor: Duration
        get() = 5.minutes

    override suspend fun calculate(auth: RequestAuth<User>): String = auth.get().email
}