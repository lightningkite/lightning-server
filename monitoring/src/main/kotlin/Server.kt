package com.lightningkite.lightningserver.monitoring

import com.lightningkite.EmailAddress
import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.cache.MemcachedCache
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.*
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.exceptions.SentryExceptionReporter
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.files.S3FileSystem
import com.lightningkite.lightningserver.files.UploadEarlyEndpoint
import com.lightningkite.lightningserver.settings.setting
import com.lightningkite.lightningserver.sms.SMSSettings
import com.lightningkite.lightningserver.tasks.startupOnce
import com.lightningkite.lightningserver.websocket.MultiplexWebSocketHandler
import com.lightningkite.lightningserver.websocket.websocket
import kotlinx.serialization.KSerializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import com.lightningkite.UUID
import com.lightningkite.lightningserver.meta.MetaEndpoints
import com.lightningkite.prepareModelsServerCore
import com.lightningkite.prepareModelsShared
import com.lightningkite.toEmailAddress

object Server : ServerPathGroup(ServerPath.root) {

    val database = setting("database", DatabaseSettings())
    val email = setting("email", EmailSettings())
    val sms = setting("sms", SMSSettings())
    val files = setting("files", FilesSettings())
    val cache = setting("cache", CacheSettings())
    val slack = setting("slack", null as String?)

    init {
        DynamoDbCache
        MongoDatabase
        MemcachedCache
        SentryExceptionReporter
        S3FileSystem
        prepareModelsShared()
        prepareModelsServerCore()
        startupOnce("adminUser", database) {
            database().collection<User>().insertOne(
                User(
                    email = "joseph+admin@lightningkite.com".toEmailAddress(),
                    role = UserRole.Admin,
                )
            )
        }
        Authentication.isDeveloper = authOptions<User>()
        Authentication.isSuperUser = AuthOptions<User>(setOf(AuthOption(AuthType<User>())))
    }

    val uploadEarly = UploadEarlyEndpoint(path("upload"), files, database)

    val user = UserEndpoints(path("user"))
    val application = ApplicationEndpoints(path("application"))
    val applicationHealthCheck = ApplicationHealthCheckEndpoints(path("applicationHealthCheck"))
    val applicationStackTrace = ApplicationStackTraceEndpoints(path("applicationStackTrace"))
    val funnel = FunnelEndpoints(path("funnel"))
    val funnelInstance = FunnelInstanceEndpoints(path("funnelInstance"))

    val meta = MetaEndpoints(path("meta"))

    val multiplex = path("multiplex").websocket(MultiplexWebSocketHandler(cache))

    val auth = AuthEndpoints(path("auth"))
}

object EmailCacheKey : RequestAuth.CacheKey<User, UUID, EmailAddress>() {
    override val name: String
        get() = "email"
    override val serializer: KSerializer<EmailAddress>
        get() = EmailAddress.serializer()
    override val validFor: Duration
        get() = 5.minutes

    override suspend fun calculate(auth: RequestAuth<User>): EmailAddress = auth.get().email
}