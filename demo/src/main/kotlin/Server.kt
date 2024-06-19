package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningdb.*
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
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.notifications.NotificationSettings
import com.lightningkite.lightningserver.typed.api
import com.lightningkite.uuid
import kotlinx.serialization.Serializable

object Server : ServerPathGroup(ServerPath.root) {

    val database = setting("database", DatabaseSettings())
    val notifications = setting("notifications", NotificationSettings())

    val root = path("/").get.handler {
        HttpResponse.plainText("Hello world!")
    }
    val newEndpoint = path("test").post.api(
        summary = "Test Endpoint",
        authOptions = noAuth,
        implementation = { it: TestObj ->
            val result = database().collection<TestObj>().insertOne(it)
            notifications().send(listOf("target token here"), "title")
            return@api result ?: throw BadRequestException()
        }
    )
}
@Serializable
data class TestObj(
    val x: Int,
    val y: String
)