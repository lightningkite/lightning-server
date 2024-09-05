@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningdb.prepareModels
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.auth.oauth.OauthClientEndpoints
import com.lightningkite.lightningserver.auth.proof.*
import com.lightningkite.lightningserver.auth.subject.AuthEndpointsForSubject
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.*
import com.lightningkite.lightningserver.email.Email
import com.lightningkite.lightningserver.email.EmailLabeledValue
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.engine.UnitTestEngine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.files.UploadEarlyEndpoint
import com.lightningkite.lightningserver.logging.LoggingSettings
import com.lightningkite.lightningserver.logging.loggingSettings
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.settings.setting
import com.lightningkite.lightningserver.sms.SMSSettings
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.testmodels.*
import com.lightningkite.uuid
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration
import java.util.*
import com.lightningkite.UUID
import kotlin.time.Duration.Companion.minutes

object TestSettings: ServerPathGroup(ServerPath.root) {
    val database = setting("database", DatabaseSettings("ram"))
    val email = setting("email", EmailSettings("test"))
    val sms = setting("sms", SMSSettings("test"))
    val cache = setting("cache", CacheSettings())
    val files = setting("files", FilesSettings())


    val authPath = ServerPath("auth")

    init {
        Authentication.isDeveloper = authRequired<TestUser> { it.get().isSuperAdmin }
        Authentication.isAdmin = authRequired<TestUser> { it.get().isSuperAdmin }
        Authentication.isSuperUser = authRequired<TestUser> { it.get().isSuperAdmin }
    }

    val earlyUpload = UploadEarlyEndpoint(path("upload"), files, database)

    val wsModelInfo = modelInfo<HasId<*>?, TestThing, UUID>(
        authOptions = noAuth,
        serialization = ModelSerializationInfo(),
        getBaseCollection = { database().collection() },
        forUser = { it },
    )
    val ws = ServerPath("test").restApiWebsocket<HasId<*>?, TestThing, UUID>(
        database,
        info = wsModelInfo
    )
    val ws2 = ServerPath("test2").restApiWebsocket<HasId<*>?, TestThing, UUID>(
        database,
        info = wsModelInfo,
        key = TestThing__id
    )


    init {
        prepareModels()
        com.lightningkite.lightningserver.testmodels.prepareModels()
    }

    val userInfo = modelInfoWithDefault<TestUser, TestUser, UUID>(
        getBaseCollection = {
            database().collection<TestUser>()
        },
        defaultItem = { TestUser(email = "") },
        forUser = { it },
        authOptions = authOptions<TestUser>(),
        serialization = ModelSerializationInfo()
    )

    val proofEmail = EmailProofEndpoints(
        ServerPath(uuid().toString()),
        PinHandler(cache, "pin"),
        email,
        { to, pin ->
            Email(
                subject = "Log In Code",
                to = listOf(EmailLabeledValue(to)),
                plainText = "Your PIN is $pin."
            )
        }
    )

    val proofPassword = PasswordProofEndpoints(
        ServerPath(uuid().toString()),
        database,
        cache
    )
    val proofOtp = OneTimePasswordProofEndpoints(
        ServerPath(uuid().toString()),
        database,
        cache
    )
    val proofSms = SmsProofEndpoints(
        ServerPath(uuid().toString()),
        PinHandler(cache, "pin2"),
        sms,
    )
    val subjectHandler = object : Authentication.SubjectHandler<TestUser, UUID> {
        override val name: String get() = "TestUser"
        override val authType: AuthType get() = AuthType<TestUser>()

        override suspend fun findUser(property: String, value: String): TestUser? {
            try {
                return when (property) {
                    "email" -> userInfo.collection().findOne(condition { it.email eq value }) ?: run {
                        userInfo.collection().insertOne(TestUser(email = value))
                    }

                    "phone" -> userInfo.collection().find(condition { it.phoneNumber eq value }).toList().singleOrNull()
                    "_id" -> userInfo.collection().get(uuid(value))
                    else -> null
                }
            } catch(e: Exception) {
                throw Exception("Failed to find $property = $value", e)
            }
        }

        override fun get(property: String): Boolean = super.get(property) || property == "phoneNumber"
        override fun get(subject: TestUser, property: String): String? {
            return when(property) {
                "phone" -> subject.phoneNumber
                else -> super.get(subject, property)
            }
        }

        override suspend fun desiredStrengthFor(result: TestUser): Int = if(result.isSuperAdmin) 20 else 5

        override suspend fun permitMasquerade(
            other: Authentication.SubjectHandler<*, *>,
            request: RequestAuth<TestUser>,
            otherId: Comparable<*>
        ): Boolean {
            return other == this && request.get().isSuperAdmin && !fetch(otherId as UUID).isSuperAdmin
        }

        override val idSerializer: KSerializer<UUID>
            get() = userInfo.serialization.idSerializer
        override val subjectSerializer: KSerializer<TestUser>
            get() = userInfo.serialization.serializer

        override suspend fun fetch(id: UUID): TestUser = userInfo.collection().get(id) ?: throw NotFoundException()
        override val knownCacheTypes: List<RequestAuth.CacheKey<TestUser, UUID, *>> = listOf(EmailCacheKey, TestCacheKey, TestCacheKey2)
        override fun toString(): String = name
    }
    val testUserSubject = AuthEndpointsForSubject(
        path = ServerPath(uuid().toString()),
        handler = subjectHandler,
        database = database,
    )

    object EmailCacheKey : RequestAuth.CacheKey<TestUser, UUID, String>() {
        override val name: String
            get() = "email"
        override val serializer: KSerializer<String> = String.serializer()
        override val validFor: Duration
            get() = 5.minutes
        override suspend fun calculate(auth: RequestAuth<TestUser>): String = auth.get().email
    }

    object TestCacheKey : RequestAuth.CacheKey<TestUser, UUID, UUID>() {
        override val name: String
            get() = "uuid"
        override val serializer: KSerializer<UUID>
            get() = ContextualSerializer(UUID::class)
        override val validFor: Duration
            get() = 5.minutes
        override suspend fun calculate(auth: RequestAuth<TestUser>): UUID = auth.id
    }
    object TestCacheKey2: RequestAuth.CacheKey<TestUser, UUID, CompletePermissions>() {
        override val name: String
            get() = "permissions"
        override val serializer: KSerializer<CompletePermissions>
            get() = CompletePermissions.serializer()
        override val validFor: Duration
            get() = 5.minutes
        override suspend fun calculate(auth: RequestAuth<TestUser>): CompletePermissions = CompletePermissions.sample
    }

    suspend fun RequestAuth<TestUser>.email() = this.get(EmailCacheKey)

    val testUser = GlobalScope.async(start = CoroutineStart.LAZY) { userInfo.collection().insertOne(TestUser(email = "test@test.com"))!! }
    val testAdmin = GlobalScope.async(start = CoroutineStart.LAZY) { userInfo.collection().insertOne(TestUser(email = "admin@test.com", isSuperAdmin = true))!! }
    val oauthClients = OauthClientEndpoints(path("oauth-clients"), database)

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




interface Permissions<Whole, Crud> {
    val manageBalance: Whole
    val minimalMemberRead: Whole
    val notifications: Whole
    val sds: Whole
    val subscriptions: Whole

    // Access Based Permissions
    val associates: Crud
    val applicants: Crud
    val billing: Crud
    val content: Crud
    val documents: Crud
    val exclusionMatches: Crud
    val memberDocuments: Crud
    val members: Crud
    val organizations: Crud
    val policies: Crud
    val policyAnswers: Crud
    val roles: Crud
    val tags: Crud
    val tasks: Crud
    val taskSchedules: Crud
}

interface ServicePermissions<Whole, Crud> {
    val forms: Crud
    val content: Crud
    val policies: Crud
    val policyQuestions: Crud
    val products: Crud
    val roles: Crud
    val sds: Crud
    val tags: Crud
}

@Serializable
data class FinalServicePermissions(
    override val forms: Access = Access.None,
    override val content: Access = Access.None,
    override val policies: Access = Access.None,
    override val policyQuestions: Access = Access.None,
    override val products: Access = Access.None,
    override val roles: Access = Access.None,
    override val sds: Access = Access.None,
    override val tags: Access = Access.None,
) : ServicePermissions<Boolean, Access>

@Serializable
enum class Access {
    None,
    View,
    Edit,
    Full,
    Delegate,
    Administrate,
}

@Serializable
data class FinalPermissions(
    val directOwner: UUID,
    val owners: Set<UUID>,
    val member: UUID,
    override val manageBalance: Boolean,
    override val minimalMemberRead: Boolean,
    override val notifications: Boolean,
    override val sds: Boolean,
    override val subscriptions: Boolean,
    override val associates: Access,
    override val applicants: Access,
    override val billing: Access,
    override val content: Access,
    override val documents: Access,
    override val exclusionMatches: Access,
    override val organizations: Access,
    override val policies: Access,
    override val policyAnswers: Access,
    override val roles: Access,
    override val tags: Access,
    override val tasks: Access,
    override val taskSchedules: Access,
    override val memberDocuments: Access,
    override val members: Access,
) : Permissions<Boolean, Access>

@Serializable
data class CompletePermissions(
    val organizations: Set<FinalPermissions>,
    val services: FinalServicePermissions,
) {
    companion object {
        val sample by lazy {
            Serialization.json.decodeFromString(
                serializer(), """
            {
            	"organizations": [
            		{
            			"directOwner": "85ee13e4-71ac-4474-bc21-1bcd392f889a",
            			"owners": [
            				"85ee13e4-71ac-4474-bc21-1bcd392f889a"
            			],
            			"member": "3a8a8f0e-845d-4783-b5bb-d56c68fa8f2d",
            			"manageBalance": false,
            			"minimalMemberRead": false,
            			"notifications": false,
            			"sds": false,
            			"subscriptions": false,
            			"associates": "None",
            			"applicants": "None",
            			"billing": "None",
            			"content": "None",
            			"documents": "None",
            			"exclusionMatches": "None",
            			"organizations": "None",
            			"policies": "None",
            			"policyAnswers": "None",
            			"roles": "None",
            			"tags": "None",
            			"tasks": "None",
            			"taskSchedules": "None",
            			"memberDocuments": "None",
            			"members": "None"
            		}
            	],
            	"services": {
            		"forms": "None",
            		"content": "None",
            		"policies": "None",
            		"policyQuestions": "None",
            		"products": "None",
            		"roles": "None",
            		"sds": "None",
            		"tags": "None"
            	}
            }
        """.trimIndent()
            )
        }
    }
}