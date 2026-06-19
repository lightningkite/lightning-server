package com.lightningkite.lightningserver.guide.samples

// region auth-imports
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.auth
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.Uuid
// endregion auth-imports

// region user-model
@Serializable
@GenerateDataClassPaths
data class UserProfile(
    override val _id: Uuid = Uuid.random(),
    val name: String,
    val email: String,
) : HasId<Uuid> {
    // The companion implements PrincipalType so this type can be used as an auth subject.
    // It tells the framework how to serialize IDs and how to load a subject from storage.
    companion object : PrincipalType<UserProfile, Uuid> {
        override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
        override val subjectSerializer: KSerializer<UserProfile> = serializer()

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): UserProfile =
            UserProfileServer.database().table<UserProfile>().get(id)
                ?: throw NotFoundException("User not found")
    }
}
// endregion user-model

// region auth-server
object UserProfileServer : ServerBuilder() {
    val database = setting("database", Database.Settings())

    init {
        // register() makes this principal type discoverable when deserializing tokens.
        register(UserProfile)
    }

    // GET /profile — requires a UserProfile authentication token
    val getProfile = path.path("profile").get bind ApiHttpHandler(
        summary = "Get current user profile",
        // UserProfile.require() returns an AuthRequirement that accepts only tokens issued for UserProfile.
        // Compare to noAuth (AuthRequirement.None) used in earlier chapters.
        auth = UserProfile.require(),
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
fun authTest() = runBlocking {
    UserProfileServer.test(settings = { database set Database.Settings("ram") }) {
        // Seed a user directly into the database
        val alice = UserProfileServer.database().table<UserProfile>()
            .insertOne(UserProfile(name = "Alice", email = "alice@example.com"))!!

        // testAuth() creates an Authentication<UserProfile> for use in tests.
        // It must be called inside a test {} block because it needs a ServerRuntime in context.
        val aliceAuth = UserProfile.testAuth(alice)

        // Pass the auth token as the first argument to the typed .test() call.
        val profile = UserProfileServer.getProfile.test(aliceAuth, Unit)
        check(profile.name == "Alice")
        check(profile.email == "alice@example.com")
        check(profile._id == alice._id)
    }
}
// endregion auth-test
