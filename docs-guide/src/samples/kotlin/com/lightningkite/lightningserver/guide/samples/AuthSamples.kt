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
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.*
// endregion auth-imports

// region user-model
@Serializable
@GenerateDataClassPaths
data class UserProfile(
    override val _id: Uuid = Uuid.random(),
    val name: String,
    val email: String,
) : HasId<Uuid>
// endregion user-model

// region user-auth
// PrincipalType lives separately from the model so UserProfile can reside in a
// shared/models module that carries no server dependencies.
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

    // registerTable defines the table, registers it, and creates its once-per-deploy prepare task.
    // Access it at runtime by invoking it: userProfiles().
    val userProfiles = database.registerTable<UserProfile>("UserProfile")

    init {
        // register() makes this principal type discoverable when deserializing tokens.
        register(UserAuth)
        // registerBasicMediaTypeCoders() enables JSON serialization of HTTP request/response bodies,
        // including error responses. Required when testing via HttpHandler.test() (the full HTTP pipeline).
        registerBasicMediaTypeCoders()
    }

    // GET /profile — requires a UserProfile authentication token
    val getProfile = path.path("profile").get bind ApiHttpHandler(
        summary = "Get current user profile",
        // UserAuth.require() returns an AuthRequirement that accepts only tokens issued for UserProfile.
        // Compare to noAuth (AuthRequirement.None) used in earlier chapters.
        auth = UserAuth.require(),
        successCode = HttpStatus.OK,
        errorCases = emptyList(),
        implementation = { _: Unit ->
            // Inside an authenticated handler, `auth` is Authentication<UserProfile>.
            // auth.id gives the Uuid; auth.fetch() loads the full UserProfile from the database.
            auth.fetch()
        }
    )
}
// endregion auth-server

// region auth-test
fun authTest() = UserProfileServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    // Seed a user directly into the database
    val alice = UserProfileServer.userProfiles()
        .insertOne(UserProfile(name = "Alice", email = "alice@example.com"))

    // testAuth() creates an Authentication<UserProfile> for use in tests.
    // It must be called inside a testBlocking {} block because it needs a ServerRuntime in context.
    val aliceAuth = UserAuth.testAuth(alice)

    // Pass the auth token as the first argument to the typed .test() call.
    val profile = UserProfileServer.getProfile.test(aliceAuth, Unit)
    check(profile.name == "Alice")
    check(profile.email == "alice@example.com")
    check(profile._id == alice._id)
}
// endregion auth-test

// region auth-rejection-test
fun authRejectionTest() = UserProfileServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    // Drive the endpoint as an HttpHandler (not ApiHttpHandler.test()) so the full
    // auth-checking pipeline runs. The framework serializes the rejection into an HTTP
    // response; inspect .status.code on the returned HttpResponse.
    // Note: the framework throws ForbiddenException (403) when no credentials are
    // supplied, which is distinct from an invalid token (401).
    val response = UserProfileServer.getProfile.test()
    check(response.status.code == 403)
}
// endregion auth-rejection-test
