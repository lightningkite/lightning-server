package com.lightningkite.lightningserver

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningdb.prepareModels
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.auth.proof.EmailProofEndpoints
import com.lightningkite.lightningserver.auth.proof.PinHandler
import com.lightningkite.lightningserver.auth.proof.Proof
import com.lightningkite.lightningserver.auth.proof.ProofOption
import com.lightningkite.lightningserver.auth.subject.AuthEndpointsForSubject
import com.lightningkite.lightningserver.auth.token.PublicTinyTokenFormat
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.*
import com.lightningkite.lightningserver.testmodels.TestThing
import com.lightningkite.lightningserver.testmodels.TestThing__id
import com.lightningkite.lightningserver.email.Email
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.encryption.SecureHasherSettings
import com.lightningkite.lightningserver.engine.UnitTestEngine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.logging.LoggingSettings
import com.lightningkite.lightningserver.logging.loggingSettings
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.settings.setting
import com.lightningkite.lightningserver.sms.SMSSettings
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.testmodels.TestUser
import com.lightningkite.lightningserver.testmodels.email
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import java.util.*

object TestSettings {
    val database = setting("database", DatabaseSettings("ram"))
    val email = setting("email", EmailSettings("test"))
    val sms = setting("sms", SMSSettings("test"))
    val jwtSigner = setting("jwt", SecureHasherSettings())
    val cache = setting("cache", CacheSettings())
    val files = setting("files", FilesSettings())

    val path = ServerPath("auth")

    val ws = ServerPath("test").restApiWebsocket<HasId<*>?, TestThing, UUID>(
        database,
        info = modelInfo(
            authOptions = noAuth,
            serialization = ModelSerializationInfo(),
            getCollection = { database().collection() },
            forUser = { it },
        )
    )
    val ws2 = ServerPath("test2").restApiWebsocket<HasId<*>?, TestThing, UUID>(
        database,
        info = modelInfo(
            authOptions = noAuth,
            serialization = ModelSerializationInfo(),
            getCollection = { database().collection() },
            forUser = { it },
        ),
        key = TestThing__id
    )


    init {
        prepareModels()
        com.lightningkite.lightningserver.testmodels.prepareModels()
    }

    val userInfo = modelInfoWithDefault<TestUser, TestUser, UUID>(
        getCollection = {
            database().collection<TestUser>()
        },
        defaultItem = { TestUser(email = "") },
        forUser = { it },
        authOptions = authOptions<TestUser>(),
        serialization = ModelSerializationInfo()
    )

    val proofEmail = EmailProofEndpoints(
        ServerPath(UUID.randomUUID().toString()),
        jwtSigner,
        PinHandler(cache, "pin"),
        email,
        Email(
            subject = "Log In Code",
            to = listOf(),
            plainText = "Your PIN is {{PIN}}."
        )
    )

    //    val proofOtp = OneTimePasswordProofEndpoints(
//        ServerPath(UUID.randomUUID().toString()),
//        TestSettings.jwtSigner,
//        TestSettings.database,
//        TestSettings.cache
//    )
    val subject = object : Authentication.SubjectHandler<TestUser, UUID> {
        override val name: String get() = "User"
        override val idProofs: Set<Authentication.ProofMethod> = setOf(proofEmail)
        override val authType: AuthType get() = AuthType<TestUser>()
        override val applicableProofs: Set<Authentication.ProofMethod> = setOf()
        override suspend fun authenticate(vararg proofs: Proof): Authentication.AuthenticateResult<TestUser, UUID>? {
            val emailIdentifier = proofs.find { it.of == "email" } ?: return null
            val user = userInfo.collection().findOne(condition { it.email eq emailIdentifier.value }) ?: run {
                userInfo.collection().insertOne(
                    TestUser(
                        email = emailIdentifier.value
                    )
                )
            } ?: return null
            val options = listOfNotNull(
                ProofOption(proofEmail.info, user.email),
//                proofOtp.proofOption(this, user._id),
            )
            return Authentication.AuthenticateResult(
                id = user._id,
                subjectCopy = user,
                options = options,
                strengthRequired = 15
            )
        }

        override val idSerializer: KSerializer<UUID>
            get() = userInfo.serialization.idSerializer
        override val subjectSerializer: KSerializer<TestUser>
            get() = userInfo.serialization.serializer

        override suspend fun fetch(id: UUID): TestUser = userInfo.collection().get(id) ?: throw NotFoundException()
        override val knownCacheTypes: List<RequestAuth.CacheKey<TestUser, UUID, *>> = listOf(EmailCacheKey)
    }
    val testUserSubject = AuthEndpointsForSubject(
        path = ServerPath(UUID.randomUUID().toString()),
        handler = subject,
        database = database,
        proofHasher = jwtSigner,
        tokenFormat = { PublicTinyTokenFormat(jwtSigner) }
    )

    object EmailCacheKey : RequestAuth.CacheKey<TestUser, UUID, String>() {
        override val name: String
            get() = "email"
        override val serializer: KSerializer<String> = String.serializer()
        override suspend fun calculate(auth: RequestAuth<TestUser>): String = auth.get().email
    }
    object TestCacheKey: RequestAuth.CacheKey<TestUser, UUID, UUID>() {
        override val name: String
            get() = "uuid"
        override val serializer: KSerializer<UUID>
            get() = ContextualSerializer(UUID::class)
        override suspend fun calculate(auth: RequestAuth<TestUser>): UUID = UUID.randomUUID()
    }

    suspend fun RequestAuth<TestUser>.email() = this.get(EmailCacheKey)

    val testUser = GlobalScope.async(start = CoroutineStart.LAZY) { userInfo.collection().insertOne(TestUser(email = "test@test.com"))!! }

    init {
        Settings.populateDefaults(
            mapOf(
                generalSettings.name to GeneralServerSettings(debug = true),
                loggingSettings.name to LoggingSettings(
                    default = LoggingSettings.ContextSettings(
                        filePattern = null,
                        toConsole = true,
                        level = "DEBUG"
                    )
                )
            )
        )
        runBlocking {
            Tasks.onSettingsReady()
            engine = UnitTestEngine
            Tasks.onEngineReady()
        }
    }

}