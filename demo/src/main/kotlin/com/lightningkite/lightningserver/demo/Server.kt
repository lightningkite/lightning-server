@file:Suppress("unused")

package com.lightningkite.lightningserver.demo

import com.lightningkite.DataSize.Companion.bytes
import com.lightningkite.lightningserver.ai.ExternalChannelSupport
import com.lightningkite.lightningserver.ai.VoiceChannelSupport
import com.lightningkite.lightningserver.demo.endpoints.*
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.cors.CorsInterceptor
import com.lightningkite.lightningserver.cors.CorsSettings
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.deprecations.path1
import com.lightningkite.lightningserver.files.UploadEarlyEndpoint
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.pathing.arg2
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.sessions.*
import com.lightningkite.lightningserver.sessions.proofs.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.route
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.ai.koog.LLMClientAndModelSettings
import com.lightningkite.services.cache.*
import com.lightningkite.services.cache.dynamodb.*
import com.lightningkite.services.cache.memcached.*
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.*
import com.lightningkite.services.database.jsonfile.JsonFileDatabase
import com.lightningkite.services.database.mongodb.*
import com.lightningkite.services.email.*
import com.lightningkite.services.email.javasmtp.JavaSmtpEmailService
import com.lightningkite.services.email.ses.SesEmailInboundService
import com.lightningkite.services.files.*
import com.lightningkite.services.files.s3.*
import com.lightningkite.services.http.*
import com.lightningkite.services.otel.OpenTelemetrySettings
import com.lightningkite.services.sms.*
import com.lightningkite.services.sms.twilio.TwilioSMS
import com.lightningkite.services.sms.twilio.TwilioSmsInboundService
import com.lightningkite.services.phonecall.PhoneCallService
import com.lightningkite.services.phonecall.twilio.TwilioPhoneCallService
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.aws.AwsWebSocketPubSub
import com.lightningkite.services.voiceagent.VoiceAgentService
import com.lightningkite.services.voiceagent.openai.OpenAIVoiceAgentService
import com.lightningkite.toPhoneNumber
import io.ktor.client.request.*
import io.ktor.server.plugins.NotFoundException
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
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
    val llm = setting("llm", LLMClientAndModelSettings(""))
    val email = setting("email", EmailService.Settings())
    val emailInbound = setting("emailInbound", EmailInboundService.Settings())
    val sms = setting("sms", SMS.Settings())
    val smsInbound = setting("smsInbound", SmsInboundService.Settings())
    val files = setting("files", PublicFileSystem.Settings())
    val cache = setting("cache", Cache.Settings())
    val cors = setting("cors", CorsSettings())
    val voiceAgent = setting("voiceAgent", VoiceAgentService.Settings())
    val pubsub = setting("pubSub", PubSub.Settings())
    val phoneCall = setting("phoneCall", PhoneCallService.Settings())
    val newSecret = setting("someSecret", "???", instructions = "This can be whatever you dream, you madman.")

    val corsInterceptor = install(CorsInterceptor(cors))

    init {
        JavaSmtpEmailService
        SesEmailInboundService
        TwilioSmsInboundService
        TwilioSMS
        TwilioPhoneCallService
        JsonFileDatabase
        DynamoDbCache
        MongoDatabase
        MemcachedCache
        S3PublicFileSystem
        OpenAIVoiceAgentService
        AwsWebSocketPubSub
    }

    val setupAdmins = path.path("setup-admins2") bind startupOnce(database) {
        userInfo.table().insertOne(User(email = "joseph@lightningkite.com", isSuperUser = true, phone = "+18013693729".toPhoneNumber()))
    }

    object UserAuth: PrincipalType<User, Uuid> {
        override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
        override val subjectSerializer: KSerializer<User> = User.Companion.serializer()
        override val name: String get() = User.serializer().descriptor.serialName.substringAfterLast('.')

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): User = userInfo.table().get(id) ?: throw com.lightningkite.lightningserver.NotFoundException()

        context(server: ServerRuntime)
        override suspend fun fetchByProperty(property: String, value: String): User? {
            return when(property) {
                "email" -> return userInfo.table().findOne(condition { it.email eq value }) ?: userInfo.table().insertOne(User(email = value))
                else -> super.fetchByProperty(property, value)
            }
        }
    }

    val userInfo: ModelInfo<User?, User, Uuid> = database.modelInfo(
        auth = UserAuth.require() or AuthRequirement.None,
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

    /**
     * Basic Examples - Simple HTTP endpoints demonstrating fundamental patterns.
     */
    val basic = path include BasicExamplesEndpoints

    /**
     * Typed API Examples - Type-safe endpoints with auto-documentation.
     */
    val typedApi = path include TypedApiExamplesEndpoints

    val blog = path.path("blog") module BlogEndpoints
//    val comment = path.path("comment") module CommentEndpoints

    /**
     * File Examples - File upload, download, and signed URLs.
     */
    val fileExamples = path.path("files") module FileExamplesEndpoints(files, database)

    /**
     * WebSocket Examples - Real-time communication patterns.
     */
    val websocketExamples = path include WebSocketExamplesEndpoints

    /**
     * Cache Examples - Caching patterns and operations.
     */
    val cacheExamples = path.path("cache") module CacheExamplesEndpoints(cache)

    /**
     * Task Examples - Background tasks and scheduled jobs.
     */
    val taskExamples = path.path("tasks") module TaskExamplesEndpoints(cache)

    /**
     * JSON-RPC Examples - RPC-style API endpoints using JSON-RPC 2.0 protocol.
     */
    val jsonRpcExamples = path include JsonRpcExamplesEndpoints

    val multiplex = path.path("multiplex") bind MultiplexWebSocketHandler()

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

    val blogAssist = BlogAssistantChat(database, llm, blogPostInfo = blog.info)
    val blogAssistEndpoints = path.path("assistant") module blogAssist
    val blogAssistChannels = path.path("assistant-channels") module ExternalChannelSupport(
        chatEndpoints = blogAssist,
        principalType = UserAuth,
        smsInbound = smsInbound,
        smsOutbound = sms,
        emailInbound = emailInbound,
        emailOutbound = email,
        files = files,
        resolveSubjectByEmail = { emailAddr ->
            userInfo.table().findOne(condition { it.email eq emailAddr.raw })
        },
        resolveSubjectByPhone = {  phone ->
            userInfo.table().findOne(condition { it.phone eq phone })
        },
    )

    val blogAssistVoice = path.path("assistant-voice") module VoiceChannelSupport(
        chatEndpoints = blogAssist, // Provides tools and voice instructions
        authRequirement = UserAuth.require(),
        principalType = UserAuth,
        voiceAgent = voiceAgent,
        pubsub = pubsub,
        phoneCall = phoneCall, // Enable phone call integration
        resolveSubjectByPhone = { phone ->
            userInfo.table().findOne(condition { it.phone eq phone })
        },
        historyMessageLimit = 10,
    )

    init { registerBasicMediaTypeCoders() }
}
