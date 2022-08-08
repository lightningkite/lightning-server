@file:UseContextualSerialization(Instant::class, UUID::class, ServerFile::class)

package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.db.*
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.exceptions.ExceptionSettings
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.logging.LoggingSettings
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningserver.settings.loadSettings
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.aws.terraformAws
import com.lightningkite.lightningserver.azure.terraformAzure
import com.lightningkite.lightningserver.cache.*
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.routing
import com.lightningkite.lightningserver.email.SesClient
import com.lightningkite.lightningserver.files.S3FileSystem
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.handler
import com.lightningkite.lightningserver.http.test
import com.lightningkite.lightningserver.ktor.runServer
import com.lightningkite.lightningserver.pubsub.LocalPubSub
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.serialization.parsingFileSettings
import com.lightningkite.lightningserver.serverhealth.healthCheck
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.settings.setting
import com.lightningkite.lightningserver.tasks.task
import com.lightningkite.lightningserver.websocket.websocket
import kotlinx.coroutines.delay
import kotlinx.serialization.*
import java.io.File
import java.lang.Exception
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.random.Random

@Serializable
@DatabaseModel
data class TestModel(
    override val _id: UUID = UUID.randomUUID(),
    val timestamp: Instant = Instant.now(),
    val name: String = "No Name",
    val number: Int = 3123,
    @JsonSchemaFormat("jodit") val content: String = "",
    val file: ServerFile? = null
) : HasId<UUID>

@Serializable
@DatabaseModel
data class User(
    override val _id: UUID = UUID.randomUUID(),
    override val email: String
) : HasId<UUID>, HasEmail

object Server {

    val database: Settings.Requirement<DatabaseSettings, Database> = setting("database", DatabaseSettings())
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

    val root = ServerPath.root.get.handler { HttpResponse.plainText("Hello ${it.rawUser()}") }

    val socket = ServerPath.root.websocket(
        connect = { println("Connected $it") },
        message = { println("Message $it") },
        disconnect = { println("Disconnect $it") }
    )

    init {
        routing {
            get.handler { HttpResponse.plainText("Hello ${it.rawUser()}") }
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
            path("cache") {
                get("check").handler {
                    val fromCache = cache().get<Int>("key")
                    println("From cache: $fromCache")
                    HttpResponse.plainText(fromCache.toString())
                }
                get("type").handler {
                    HttpResponse.plainText("Cache type: ${cache()::class.qualifiedName} from settings ${Settings.current()[cache.name]}")
                }
                get("reset").handler {
                    val number = Random.nextInt(0, 100)
                    cache().set("key", number, Duration.ofHours(1))
                    println("Resetting cached key to ${number}")
                    HttpResponse.plainText(number.toString())
                }
            }
            path("run-task").get.handler {
                val number = Random.nextInt(0, 100)
                task(number)
                HttpResponse.plainText("OK")
            }
            path("auth").authEndpoints(
                jwtSigner = jwtSigner,
                database = database,
                email = email,
                onNewUser = { User(email = it) }).authEndpointExtensionHtml()
            path("test-model") {
                path("rest").restApi(database) { user: User? -> database().collection<TestModel>("TestModel") }
                path("rest").restApiWebsocket<User, TestModel, UUID>(
                    database,
                    { it.collection<TestModel>() as AbstractSignalFieldCollection<TestModel> },
                    { this })
                path("admin").adminPages(
                    database,
                    { user: User? -> TestModel() }) { user: User? -> database().collection<TestModel>("TestModel") }
            }
            path("docs").apiHelp()
            path("test-primitive").get.typed(
                summary = "Get Test Primitive",
                errorCases = listOf(),
                implementation = { user: User?, input: Unit -> "42 is great" }
            )
            path("die").get.handler { throw Exception("OUCH") }
        }
        schedule("test-schedule", Duration.ofMinutes(1)) {
            println("Hello schedule!")
        }
    }
}

fun main(vararg args: String) {
    Server
//    loadSettings(File("settings.json"))
//    runServer(LocalPubSub, LocalCache)

//    println(Documentable.kotlinApi("test"))

    terraformAws("com.lightningkite.lightningserver.demo.AwsHandler", "demo", File("demo/terraform"))

//    println(buildString { terraformAzure("demo", this) })
}