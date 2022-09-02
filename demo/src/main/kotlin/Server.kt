package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningdb.MongoDatabase
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.cache.MemcachedCache
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.email.SesClient
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.files.S3FileSystem
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.handler
import com.lightningkite.lightningserver.meta.metaEndpoints
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.serialization.parsingFileSettings
import com.lightningkite.lightningserver.settings.setting
import com.lightningkite.lightningserver.tasks.task
import com.lightningkite.lightningserver.typed.apiHelp
import com.lightningkite.lightningserver.typed.typed
import com.lightningkite.lightningserver.websocket.websocket
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
        SesClient
        MongoDatabase
        MemcachedCache
        S3FileSystem
        parsingFileSettings = files
        prepareModels()
    }

    val auth = AuthEndpoints(
        path = path("auth"),
        jwtSigner = jwtSigner,
        database = database,
        email = email,
        onNewUser = { User(email = it) }
    )
    val authHtml = AuthEndpointsHtml(auth)
    val testModel = TestModelEndpoints(path("test-model"))

    val root = path.get.handler {
        println("haha get rooted loser")
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

    val meta = path("meta").metaEndpoints<Unit> { true }
}

