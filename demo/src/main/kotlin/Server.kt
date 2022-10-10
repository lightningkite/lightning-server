package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningdb.MongoDatabase
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.cache.MemcachedCache
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.db.DynamoDbCache
import com.lightningkite.lightningserver.db.PostgresDatabase
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.email.SesClient
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
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.tasks.task
import com.lightningkite.lightningserver.typed.apiHelp
import com.lightningkite.lightningserver.typed.typed
import com.lightningkite.lightningserver.websocket.websocket
import io.ktor.client.*
import io.ktor.client.request.*
import kotlinx.coroutines.delay
import java.time.Duration
import java.util.*
import kotlin.random.Random

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
        S3FileSystem
        prepareModels()
        Tasks.onSettingsReady {
            Metrics.main()
            println("Files started, got ${files().root.url}")
        }
        Serialization.handler(FileRedirectHandler)
    }

    val auth = AuthEndpoints(
        path = path("auth"),
        jwtSigner = jwtSigner,
        database = database,
        email = email,
        onNewUser = { User(email = it) }
    )
    val uploadEarly = UploadEarlyEndpoint(path("upload"), files, database, jwtSigner)
    val authHtml = AuthEndpointsHtml(auth)
    val testModel = TestModelEndpoints(path("test-model"))

    val root = path.get.handler {
        HttpResponse.plainText("Hello ${it.rawUser()}")
    }

    val socket = path("socket").websocket(
        connect = { println("Connected $it") },
        message = { println("Message $it") },
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
    val die = path("die").get.handler { throw Exception("OUCH") }

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

    val meta = path("meta").metaEndpoints<Unit> { true }
}

