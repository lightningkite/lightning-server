package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.cache.MemcachedCache
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.*
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.email.SesClient
import com.lightningkite.lightningserver.exceptions.SentryExceptionReporter
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.files.S3FileSystem
import com.lightningkite.lightningserver.files.UploadEarlyEndpoint
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.handler
import com.lightningkite.lightningserver.meta.metaEndpoints
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.serialization.FileRedirectHandler
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.setting
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.tasks.startupOnce
import com.lightningkite.lightningserver.tasks.task
import com.lightningkite.lightningserver.typed.ApiExample
import com.lightningkite.lightningserver.typed.typed
import com.lightningkite.lightningserver.websocket.MultiplexWebSocketHandler
import com.lightningkite.lightningserver.websocket.websocket
import io.ktor.client.request.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.lang.IllegalStateException
import java.time.Duration
import java.util.*
import kotlin.random.Random
import kotlin.reflect.KType

object Server : ServerPathGroup(ServerPath.root) {

    val database = setting("database", DatabaseSettings())
    val email = setting("email", EmailSettings())
    val jwtSigner = setting("jwt", JwtSigner())
    val files = setting("files", FilesSettings())
    val cache = setting("cache", CacheSettings())

    init {
        Metrics
        SesClient
        PostgresDatabase
        DynamoDbCache
        MongoDatabase
        MemcachedCache
        SentryExceptionReporter
        S3FileSystem
        prepareModels()
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
    }

    val userInfo = ModelInfoWithDefault<User, User, UUID>(
        getCollection = {
            database().collection<User>()
                .interceptCreate { it.copy(hashedPassword = it.hashedPassword.secureHash()) }
                .interceptModification {
                    it.map(path<User>().hashedPassword) {
                        when (it) {
                            is Modification.Assign -> it.copy(it.value.secureHash())
                            else -> throw IllegalStateException()
                        }
                    }
                }
        },
        defaultItem = { User(email = "") },
        forUser = { user ->
            val everyone: Condition<User> = Condition.Always()
            val self: Condition<User> = condition { it._id eq user._id }
            val admin: Condition<User> = if (user.isSuperUser) Condition.Always() else Condition.Never()
            withPermissions(
                ModelPermissions(
                    create = everyone,
                    read = self or admin,
                    readMask = mask {
                        it.hashedPassword.maskedTo("MASKED").unless(admin)
                    },
                    update = self or admin,
                    updateRestrictions = updateRestrictions {
                        it.isSuperUser.requires(admin)
                    },
                    delete = self or admin
                )
            )
        }
    )
    val user = object : ServerPathGroup(path("user")) {
        val rest = ModelRestEndpoints(path("rest"), userInfo)
    }
    val auth = object : ServerPathGroup(path("auth")) {
        val emailAccess = userInfo.userEmailAccess { User(email = it) }
        val passAccess =
            userInfo.userPasswordAccess { username, hashed -> User(email = username, hashedPassword = hashed) }
        val baseAuth = BaseAuthEndpoints(path, emailAccess, jwtSigner)
        val emailAuth = EmailAuthEndpoints(baseAuth, emailAccess, cache, email)
        val passAuth = PasswordAuthEndpoints(baseAuth, passAccess)
    }
    val auth2 = object : ServerPathGroup(path("auth2")) {
        val info = ModelInfo<UserAlt, UserAlt, UUID>(
            getCollection = { database().collection<UserAlt>() },
            forUser = { this }
        )
        val emailAccess = info.userEmailAccess { UserAlt(email = it) }
        val baseAuth = BaseAuthEndpoints(path, emailAccess, jwtSigner)
        val emailAuth = EmailAuthEndpoints(baseAuth, emailAccess, cache, email)

        init {
            path.docName = "auth2"
        }
    }
    val uploadEarly = UploadEarlyEndpoint(path("upload"), files, database, jwtSigner)
    val testModel = TestModelEndpoints(path("test-model"))

    val root = path.get.handler {
        HttpResponse.plainText("Hello ${it.rawUser()}")
    }

    val socket = path("socket").websocket(
        connect = { println("Connected $it - you are ${it.rawUser()}") },
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
        val id = UUID.randomUUID()
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

    val databaseCheck = path("database-check").get.handler {
        HttpResponse.plainText(database().collection<User>()::class.qualifiedName ?: "???")
    }

    val testSchedule = schedule("test-schedule", Duration.ofMinutes(1)) {
        println("Hello schedule!")
    }
    val testSchedule2 = schedule("test-schedule2", Duration.ofMinutes(1)) {
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

    val meta = path("meta").metaEndpoints<Unit> { true }
}

