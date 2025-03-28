package com.lightningkite.lightningserver

import com.lightningkite.UUID
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.files.ServerFile
import com.lightningkite.lightningdb.collection
import com.lightningkite.lightningdb.insertOne
import com.lightningkite.lightningdb.test.*
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.AuthType
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.JwtSigner
import com.lightningkite.lightningserver.auth.authOptions
import com.lightningkite.lightningserver.auth.authRequired
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.auth.oauth.OauthProviderCredentials
import com.lightningkite.lightningserver.auth.oauth.OauthProviderCredentialsApple
import com.lightningkite.lightningserver.auth.old.BaseAuthEndpoints
import com.lightningkite.lightningserver.auth.old.EmailAuthEndpoints
import com.lightningkite.lightningserver.auth.old.UserEmailAccess
import com.lightningkite.lightningserver.auth.old.userEmailAccess
import com.lightningkite.lightningserver.auth.subject.AuthEndpointsForSubject
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.db.ModelSerializationInfo
import com.lightningkite.lightningserver.db.modelInfo
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.engine.LocalEngine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.files.UploadEarlyEndpoint
import com.lightningkite.lightningserver.files.fileObject
import com.lightningkite.lightningserver.filescanner.FileScanner
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.meta.metaEndpoints
import com.lightningkite.lightningserver.pubsub.LocalPubSub
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.settings.setting
import com.lightningkite.lightningserver.sms.SMSSettings
import com.lightningkite.lightningserver.typed.ApiWebsocket
import com.lightningkite.lightningserver.typed.TypedServerPath1
import com.lightningkite.lightningserver.typed.api
import com.lightningkite.lightningserver.typed.arg
import com.lightningkite.lightningserver.typed.bulkRequestEndpoint
import com.lightningkite.lightningserver.typed.post
import com.lightningkite.lightningserver.typed.typed
import com.lightningkite.prepareModelsServerCore
import com.lightningkite.prepareModelsShared
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import java.io.InputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

object TestSettings: ServerPathGroup(ServerPath.root) {
    val database = setting("database", DatabaseSettings("ram"))
    val email = setting("email", EmailSettings("test"))
    val sms = setting("sms", SMSSettings("test"))
    val cache = setting("cache", CacheSettings())
    val files = setting("files", FilesSettings())
    val jwtSigner = setting("jwtSigner", JwtSigner())
    val oauthGoogle = setting<OauthProviderCredentials?>("oauth_google", null)
    val oauthApple = setting<OauthProviderCredentialsApple?>("oauth_apple", null)
    val oauthGithub = setting<OauthProviderCredentials?>("oauth_github", null)
    val oauthMicrosoft = setting<OauthProviderCredentials?>("oauth_microsoft", null)

    init {
        Serialization.enablePublicJavaData()
        Serialization.enablePublicProtobuf()
        prepareModelsShared()
        prepareModelsServerCore()
        prepareModelsServerTesting()
    }

    val info = modelInfo<User, User, UUID>(
        getBaseCollection = { database().collection() },
        forUser = { it },
        authOptions = authOptions(),
        serialization = ModelSerializationInfo()
    )
    val emailAccess: UserEmailAccess<User, UUID> = info.userEmailAccess { User(email = it, phoneNumber = it) }
    val authPath = ServerPath("auth")
    val baseAuth = BaseAuthEndpoints(authPath, emailAccess, jwtSigner, 1.hours, 5.minutes)
    val emailAuth = EmailAuthEndpoints(baseAuth, emailAccess, cache, email)

    val earlyUpload = UploadEarlyEndpoint(path("upload-early"), files, database, fileScanner = { listOf(object: FileScanner {
        override fun requires(claimedType: ContentType): FileScanner.Requires = FileScanner.Requires.Whole
        override fun scan(claimedType: ContentType, data: InputStream) {
            println("Fake scanned ${data.readBytes().size} bytes")
        }
    }) })
    val consumeFile = path("consume-file").post.api(authOptions = noAuth, summary = "consume file") { input: ServerFile ->
        input.fileObject.signedUrl
    }

    val testWebSocket = object: ApiWebsocket<HasId<*>?, TypedServerPath1<Int>, Int, Int, Unit>(
        path("ws-test").arg<Int>("num"),
        Unit.serializer()
    ) {
        override val authOptions: AuthOptions<HasId<*>?> = noAuth
        override val inputType: KSerializer<Int> = Int.serializer()
        override val outputType: KSerializer<Int> = Int.serializer()
        override val summary: String = "Sample Web Socket"

    }

    val sample1 = path("sample1").post.api(summary = "Test1", authOptions = authOptions<User>()) { input: Int -> input + 42 }
    val sample2 = path("sample2").post.api(summary = "Test2", authOptions = noAuth) { input: Int -> input + 42 }
    val sample3 = path("sample3").post.api(summary = "Test3", authOptions = authRequired<User> { false }) { input: Int -> input + 42 }
    val sample4 = path("sample4").post.api(summary = "Test4", authOptions = noAuth) { input: ValidatedModel -> input }
    val sample5 = path("sample5").post.api(summary = "Test5", authOptions = noAuth) { input: UUID -> input }
    val sample6 = path("sample6").post.api(summary = "Test6", authOptions = noAuth) { input: SimpleLargeTestModel -> input }
    val sample7 = path("sample6").post.api(summary = "Test7", authOptions = noAuth) { input: NestedEnumTestModel -> input }
    val bulk = path("bulk").bulkRequestEndpoint()
    val meta = path("meta").metaEndpoints("com.lightningkite.lightningserver")

    init {
        Settings.populateDefaults()
        engine = LocalEngine(LocalPubSub)
    }

    @OptIn(DelicateCoroutinesApi::class)
    val sampleUser = GlobalScope.async(start = CoroutineStart.LAZY) {
        info.collection().insertOne(User(
            email = "test@test.com",
            phoneNumber = "1234567890",
            age = 42
        ))!!
    }
}