package com.lightningkite.lightningserver.guide.samples

// region auth-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.*
// endregion auth-imports

// region user-model
@Queryable
data class UserProfile(
    override val _id: Uuid = Uuid.random(),
    val name: String,
    val email: String,
    val isAdmin: Boolean = false,
) : HasId<Uuid>
// endregion user-model

// region user-auth
object UserAuth : PrincipalType<UserProfile, Uuid> {
    override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
    override val subjectSerializer: KSerializer<UserProfile> = UserProfile.serializer()

    context(server: ServerRuntime)
    override suspend fun fetch(id: Uuid): UserProfile =
        UserProfileServer.userProfiles().get(id)
            ?: throw NotFoundException("User not found")
}
// endregion user-auth

// region auth-server
object UserProfileServer : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val userProfiles = database.registerTable<UserProfile>("UserProfile")

    init {
        register(UserAuth)
        registerBasicMediaTypeCoders()
    }

    val getProfile = path.path("profile").get bind ApiHttpHandler(
        summary = "Get current user profile",
        auth = UserAuth.require(),
        implementation = { _: Unit -> auth.fetch() }
    )
}
// endregion auth-server

object AuthRequirementSamples : ServerBuilder() {
    // region optional-auth
    val greeting = path.path("greeting").get bind ApiHttpHandler(
        summary = "Greet the caller",
        auth = UserAuth.require() or noAuth,
        implementation = { _: Unit ->
            val user = authOrNull?.fetch()
            if (user == null) "Hello, guest" else "Hello, ${user.name}"
        }
    )
    // endregion optional-auth

    // region requirement-options
    val readReports = UserAuth.require(scope = RequiredScope("reports:read"))
    val recentlySignedIn = UserAuth.require(maxAge = 10.minutes)
    val adminOnly = UserAuth.require { it.fetch().isAdmin }
    // endregion requirement-options

    init {
        // region super-user
        AuthRequirement.isSuperUser = UserAuth.require { it.fetch().isAdmin }
        // endregion super-user
    }
}

// region auth-cache-key
object IsAdmin : AuthCacheKey<UserProfile, Boolean> {
    override val id = "user-is-admin"
    override val serializer = Boolean.serializer()
    override val expireAfter = 5.minutes

    context(server: ServerRuntime)
    override suspend fun calculate(input: Authentication<UserProfile>): Boolean =
        input.fetch().isAdmin
}

context(server: ServerRuntime)
suspend fun canSeeAuditLog(auth: Authentication<UserProfile>): Boolean = auth.get(IsAdmin)
// endregion auth-cache-key

// region auth-test
fun authTest() = UserProfileServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    val alice = userProfiles().insertOne(UserProfile(name = "Alice", email = "alice@example.com"))

    val profile = getProfile.test(UserAuth.testAuth(alice), Unit)
    check(profile._id == alice._id)
}
// endregion auth-test

// region auth-rejection-test
fun authRejectionTest() = UserProfileServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    val response = getProfile.test()
    check(response.status.code == 401)
    check(response.headers[HttpHeader.WWWAuthenticate]?.root == "Bearer")
}
// endregion auth-rejection-test

fun authRequirementTest() = UserProfileServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    val alice = userProfiles().insertOne(UserProfile(name = "Alice", email = "alice@example.com"))
    val admin = userProfiles().insertOne(UserProfile(name = "Root", email = "root@example.com", isAdmin = true))
    execute {
        check(!AuthRequirementSamples.adminOnly.accepts(UserAuth.testAuth(alice)))
        check(AuthRequirementSamples.adminOnly.accepts(UserAuth.testAuth(admin)))
        check(!AuthRequirementSamples.readReports.accepts(UserAuth.testAuth(alice, scopes = setOf(GrantedScope("posts")))))
        check(AuthRequirementSamples.readReports.accepts(UserAuth.testAuth(alice, scopes = setOf(GrantedScope("reports")))))
        check(!AuthRequirementSamples.recentlySignedIn.accepts(UserAuth.testAuth(alice, issuedAt = now() - 11.minutes)))
        check(AuthRequirementSamples.recentlySignedIn.accepts(UserAuth.testAuth(alice)))
        check(canSeeAuditLog(UserAuth.testAuth(admin)))
        check(!canSeeAuditLog(UserAuth.testAuth(alice)))
    }
}
