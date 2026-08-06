@file:Suppress("unused")

package com.lightningkite.lightningserver.demo

//import com.lightningkite.services.database.cassandra.*
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.cors.CorsInterceptor
import com.lightningkite.lightningserver.cors.CorsSettings
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.demo.endpoints.*
import com.lightningkite.lightningserver.files.Files
import com.lightningkite.lightningserver.files.UploadEarlyEndpoint
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.fullUrl
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.serialization.StandardWithExternalModule
import com.lightningkite.lightningserver.serialization.queryParameters
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.sessions.*
import com.lightningkite.lightningserver.sessions.proofs.*
import com.lightningkite.lightningserver.sessions.proofs.oauth.OauthProviderCredentials
import com.lightningkite.lightningserver.sessions.proofs.oauth.OauthProviderInfo
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.LoggingTelemetryBackend
import com.lightningkite.services.cache.*
import com.lightningkite.services.cache.dynamodb.*
import com.lightningkite.services.cache.memcached.*
import com.lightningkite.services.cache.redis.RedisCache
import com.lightningkite.services.data.toPhoneNumber
import com.lightningkite.services.database.*
import com.lightningkite.services.database.jsonfile.JsonFileDatabase
import com.lightningkite.services.database.mongodb.*
import com.lightningkite.services.database.validation.AnnotationValidators
import com.lightningkite.services.email.*
import com.lightningkite.services.email.javasmtp.JavaSmtpEmailService
import com.lightningkite.services.email.ses.SesEmailInboundService
import com.lightningkite.services.files.*
import com.lightningkite.services.files.s3.*
import com.lightningkite.services.http.*
import com.lightningkite.services.phonecall.PhoneCallService
import com.lightningkite.services.phonecall.twilio.TwilioPhoneCallService
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.aws.DynamoDbPubSub
import com.lightningkite.services.pubsub.redis.RedisPubSub
import com.lightningkite.services.sms.*
import com.lightningkite.services.sms.twilio.TwilioSMS
import com.lightningkite.services.sms.twilio.TwilioSmsInboundService
import com.lightningkite.services.voiceagent.VoiceAgentService
import com.lightningkite.services.voiceagent.openai.OpenAIVoiceAgentService
import io.ktor.client.request.*
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLQueryComponent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.html.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlin.random.*
import kotlin.time.*
import kotlin.uuid.*

object Server : ServerBuilder() {
//    override val annotationValidators: Runtime<AnnotationValidators> = AnnotationValidators.StandardWithExternalModule

    override val annotationValidators: Runtime<AnnotationValidators> = Runtime.Cached {
        AnnotationValidators.StandardWithExternalModule() + AnnotationValidators.Files()
    }

    val database = setting("database", Database.Settings())
    val email = setting("email", EmailService.Settings())
    val emailInbound = setting("emailInbound", EmailInboundService.Settings())
    val sms = setting("sms", SMS.Settings())
    val smsInbound = setting("smsInbound", SmsInboundService.Settings())
    val files = setting("files", ExternalFileSystem.Settings())
    val cache = setting("cache", Cache.Settings())
    val cors = setting("cors", CorsSettings())
    val voiceAgent = setting("voiceAgent", VoiceAgentService.Settings())
    val pubsub = setting("pubSub", PubSub.Settings())
    val phoneCall = setting("phoneCall", PhoneCallService.Settings())
    val newSecret = setting("someSecret", "???", instructions = "This can be whatever you dream, you madman.")
    val githubOauth = setting("githubOauth", OauthProviderCredentials("", ""))

    // Baseline security headers (X-Content-Type-Options, and HSTS over https). Installed first so it runs
    // outermost and applies to every response, including CORS-processed and error responses.
    val securityHeaders = install(SecurityHeadersInterceptor())
    // v4-style access log: one line per request naming the resolved principal (or "anonymous") and IP.
    val accessLog = install(AccessLogInterceptor())
    val corsInterceptor = install(CorsInterceptor(cors))

    init {
//        CassandraDatabase
        JavaSmtpEmailService
        SesEmailInboundService
        TwilioSmsInboundService
        TwilioSMS
        LoggingTelemetryBackend
        TwilioPhoneCallService
        RedisCache
        JsonFileDatabase
        DynamoDbCache
        MongoDatabase
        MemcachedCache
        S3ExternalFileSystem
        OpenAIVoiceAgentService
        DynamoDbPubSub
        RedisPubSub
    }

    // Seed an admin user. This runs as a pre-deploy task (once per deploy, before the new version
    // serves), and `doOnce` guards the actual insert so the seed happens only once ever, not on
    // every deploy - the sanctioned pattern for "run exactly once" pre-deploy work.
    val setupAdmins = path.path("setup-admins2") bind PreDeployTask {
        doOnce("setup-admins2", database) {
            userInfo.table().insertOne(
                User(
                    email = "joseph@lightningkite.com",
                    isSuperUser = true,
                    phone = "+18013693729".toPhoneNumber()
                )
            )
        }
    }

    object UserAuth : PrincipalType<User, Uuid> {
        override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
        override val subjectSerializer: KSerializer<User> = User.Companion.serializer()
        override val name: String get() = User.serializer().descriptor.serialName.substringAfterLast('.')

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): User =
            userInfo.table().get(id) ?: throw com.lightningkite.lightningserver.NotFoundException()

        context(server: ServerRuntime)
        override suspend fun fetchByProperty(property: String, value: String): User? {
            return when (property) {
                "email" -> return userInfo.table().findOne(condition { it.email eq value }) ?: userInfo.table()
                    .insertOne(User(email = value))

                else -> super.fetchByProperty(property, value)
            }
        }
    }

    val userInfo: ModelInfo<User?, User, Uuid> = database.modelInfo(
        auth = UserAuth.require() or AuthRequirement.None,
        tableName = "User",
        permissions = {
            val user = authOrNull?.fetch()
            val everyone: Condition<User> = Condition.Always
            val self: Condition<User> = condition { it._id eqNn user?._id }
            val admin: Condition<User> = if (user?.isSuperUser == true) Condition.Always else Condition.Never
            ModelPermissions(
                create = Condition.Never,
                read = everyone,
                readMask = mask {
                    it.hashedPassword.mask(value = "", unless = self or admin)
                    it.phone.mask(value = null, unless = self or admin)
                    it.isSuperUser.mask(value = false, unless = self or admin)
                },
                update = self or admin,
                delete = self or admin
            )
        }
    )

    val user = path.path("user") include object : ServerBuilder() {
        val rest = path.path("rest") module ModelRestEndpoints(this@Server.userInfo)
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

    // Simple test WebSocket using CoroutineWebsocketHandler for isolated testing
    val testCoroutineWs = path.path("test-coroutine-ws") include object : CoroutineWebsocketHandler() {
        override val pubSub = this@Server.pubsub

        context(serverRuntime: ServerRuntime)
        override suspend fun handle(
            request: WebSocketConnectRequest<com.lightningkite.lightningserver.pathing.PathSpec0>,
            waitForFullConnect: suspend () -> Unit,
            incoming: Flow<WebSocketFrame>,
            send: suspend (WebSocketFrame) -> Unit,
        ) {
            // Signal ready immediately
            waitForFullConnect()

            // Send a greeting to confirm connection worked
            send(WebSocketFrame.Text("""{"type":"connected","message":"CoroutineWebsocketHandler ready"}"""))

            // Echo all incoming messages with a prefix
            incoming.collect { frame ->
                when (frame) {
                    is WebSocketFrame.Text -> {
                        send(WebSocketFrame.Text("""{"type":"echo","original":"${frame.content}"}"""))
                    }

                    is WebSocketFrame.Binary -> {
                        send(WebSocketFrame.Text("""{"type":"echo","binary":true,"size":${frame.content.size}}"""))
                    }
                }
            }
        }
    }

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
    val proofOauth = path.path("proof").path("github") module OauthProofEndpoints(
        provider = OauthProviderInfo.github,
        cache = cache,
        credentials = githubOauth,
        continueUiAuthUrl = { autosignIn.location.path.resolved().fullUrl() + "?proof=" + serverRuntime.externalSerialization.json.encodeToString(Proof.serializer(), it).encodeURLQueryComponent() + "&backend=" + generalSettings().publicUrl.encodeURLQueryComponent() }
    )
    val autosignIn = path.path("auth").path("autosignin").get bind HttpHandler {
        telemetrySettings
        val proof = it.queryParameters["proof"]!!.decodeURLQueryComponent().let { serverRuntime.externalSerialization.json.decodeFromString(Proof.serializer(), it) }
        HttpResponse.plainText("OK")
    }
    val subjects = path.path("auth") module object : AuthEndpoints<User, Uuid>(
        principal = UserAuth,
        database = database,
        cache = cache,
    ) {
        context(server: ServerRuntime)
        override suspend fun requiredProofStrengthFor(subject: User): Int = 5

        context(server: ServerRuntime)
        override suspend fun sessionExpiration(subject: User): Instant? = null

        context(server: ServerRuntime)
        override suspend fun sessionStaleAfter(subject: User): Duration? = null
    }

    init {
        registerBasicMediaTypeCoders()
    }
}
