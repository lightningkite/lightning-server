package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.fetch
import com.lightningkite.lightningserver.auth.id
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.auth.or
import com.lightningkite.lightningserver.auth.require
import com.lightningkite.lightningserver.auth.testAuth
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.lightningserver.typed.auth
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.Queryable
import com.lightningkite.services.data.References
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.ModelPermissions
import com.lightningkite.services.database.TypedId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

sealed interface UserInterface<ID : UserInterface.IdType<ID>> : HasId<ID> {
    sealed interface IdType<ID : IdType<ID>> : TypedId<Uuid, ID>
}

@Queryable
data class User(
    override val _id: ID = ID(Uuid.generateV7()),
) : HasId<User.ID>, UserInterface<User.ID> {
    @Serializable
    @References(User::class)
    @JvmInline
    value class ID(override val raw: Uuid) : UserInterface.IdType<ID>

    companion object : PrincipalType<User, ID> {
        override val idSerializer: KSerializer<ID>
            get() = ID.serializer()

        override val subjectSerializer: KSerializer<User>
            get() = serializer()

        context(server: ServerRuntime)
        override suspend fun fetch(id: ID): User = User(id)
    }
}

@Queryable
data class OtherUser(
    override val _id: ID = ID(Uuid.generateV7()),
) : HasId<OtherUser.ID>, UserInterface<OtherUser.ID> {
    @Serializable
    @References(OtherUser::class)
    @JvmInline
    value class ID(override val raw: Uuid) : UserInterface.IdType<ID>

    companion object : PrincipalType<OtherUser, ID> {
        override val idSerializer: KSerializer<ID>
            get() = ID.serializer()

        override val subjectSerializer: KSerializer<OtherUser>
            get() = serializer()

        context(server: ServerRuntime)
        override suspend fun fetch(id: ID): OtherUser = OtherUser(id)
    }
}

@Queryable
data class TestModel(
    override val _id: ID = ID(Uuid.generateV7()),
) : HasId<TestModel.ID> {
    @Serializable
    @References(TestModel::class)
    @JvmInline
    value class ID(override val raw: Uuid) : TypedId<Uuid, ID>
}

/**
 * Tests endpoints whose auth requirement accepts either of two principal types that share a sealed supertype.
 */
class MultipleSubjectTypesTest {

    class TestAuthEndpoints<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
        principal: PrincipalType<SUBJECT, ID>,
        database: Runtime<Database>,
        cache: Runtime<Cache>,
    ) : AuthEndpoints<SUBJECT, ID>(principal, database, cache) {
        context(server: ServerRuntime)
        override suspend fun sessionExpiration(subject: SUBJECT): Instant? =
            com.lightningkite.lightningserver.runtime.now() + 30.days

        context(server: ServerRuntime)
        override suspend fun sessionStaleAfter(subject: SUBJECT): Duration? = 7.days

        context(server: ServerRuntime)
        override suspend fun permitAuthentication(subject: SUBJECT): Boolean = true

        context(server: ServerRuntime)
        override suspend fun requiredProofStrengthFor(subject: SUBJECT): Int = 100
    }

    object Server : ServerBuilder() {
        init { registerBasicMediaTypeCoders() }

        val database = setting("database", Database.Settings("ram"))
        val cache = setting("cache", Cache.Settings("ram"))

        val userAuth = path.path("auth").path("user") include TestAuthEndpoints(User, database, cache)
        val otherUserAuth = path.path("auth").path("other-user") include TestAuthEndpoints(OtherUser, database, cache)

        val whoAmI = path.path("who-am-i").get bind ApiHttpHandler(
            summary = "Who am I",
            auth = User.require() or OtherUser.require(),
            implementation = { _: Unit ->
                when (val id = auth.id) {
                    is User.ID -> "User ${id.raw}"
                    is OtherUser.ID -> "OtherUser ${id.raw}"
                }
            }
        )

        val whoAmIFetched = path.path("who-am-i-fetched").get bind ApiHttpHandler(
            summary = "Who am I fetched",
            auth = User.require() or OtherUser.require(),
            implementation = { _: Unit ->
                when (val subject = auth.fetch()) {
                    is User -> "User ${subject._id.raw}"
                    is OtherUser -> "OtherUser ${subject._id.raw}"
                }
            }
        )

        val maybeWhoAmI = path.path("maybe-who-am-i").get bind ApiHttpHandler(
            summary = "Maybe who am I",
            auth = User.require() or OtherUser.require() or noAuth,
            implementation = { _: Unit ->
                when (val id = authOrNull?.id) {
                    null -> "anonymous"
                    is User.ID -> "User ${id.raw}"
                    is OtherUser.ID -> "OtherUser ${id.raw}"
                }
            }
        )

        val userOnly = path.path("user-only").get bind ApiHttpHandler(
            summary = "User only",
            auth = User.require(),
            implementation = { _: Unit -> "User ${auth.id.raw}" }
        )

        // Claude: users may write, other users may only read, anonymous callers see nothing.
        val info = database.modelInfo<UserInterface<out UserInterface.IdType<*>>?, TestModel, TestModel.ID>(
            tableName = "TestModel",
            auth = User.require() or OtherUser.require() or noAuth,
            permissions = {
                when (authOrNull?.id) {
                    null -> ModelPermissions()
                    is User.ID -> ModelPermissions.allowAll()
                    is OtherUser.ID -> ModelPermissions(read = Condition.Always)
                }
            }
        )
        val rest = path.path("test-model") include ModelRestEndpoints(info)
    }

    @Test
    fun `union endpoint identifies each principal`() = runBlocking {
        Server.test({}) {
            val user = User()
            val otherUser = OtherUser()
            assertEquals("User ${user._id.raw}", Server.whoAmI.test(User.testAuth(user), Unit))
            assertEquals("OtherUser ${otherUser._id.raw}", Server.whoAmI.test(OtherUser.testAuth(otherUser), Unit))
        }
    }

    @Test
    fun `union endpoint fetches the right subject type`() = runBlocking {
        Server.test({}) {
            val user = User()
            val otherUser = OtherUser()
            assertEquals("User ${user._id.raw}", Server.whoAmIFetched.test(User.testAuth(user), Unit))
            assertEquals(
                "OtherUser ${otherUser._id.raw}",
                Server.whoAmIFetched.test(OtherUser.testAuth(otherUser), Unit)
            )
        }
    }

    @Test
    fun `principals sharing a raw uuid stay distinct`() = runBlocking {
        Server.test({}) {
            val raw = Uuid.random()
            assertEquals("User $raw", Server.whoAmI.test(User.testAuth(User(User.ID(raw))), Unit))
            assertEquals("OtherUser $raw", Server.whoAmI.test(OtherUser.testAuth(OtherUser(OtherUser.ID(raw))), Unit))
        }
    }

    @Test
    fun `single principal endpoint rejects the other principal`() = runBlocking {
        Server.test({}) {
            val raw = Uuid.random()
            assertEquals("User $raw", Server.userOnly.test(User.testAuth(User(User.ID(raw))), Unit))
            // Claude: the cast stands in for a request carrying an OtherUser token, which the compiler can't see.
            @Suppress("UNCHECKED_CAST")
            val otherUserAuth = OtherUser.testAuth(OtherUser(OtherUser.ID(raw))) as Authentication<User>
            assertFailsWith<ForbiddenException> { Server.userOnly.test(otherUserAuth, Unit) }
        }
    }

    @Test
    fun `optional union accepts anonymous and both principals`() = runBlocking {
        Server.test({}) {
            val user = User()
            val otherUser = OtherUser()
            assertEquals("anonymous", Server.maybeWhoAmI.test(null, Unit))
            assertEquals("User ${user._id.raw}", Server.maybeWhoAmI.test(User.testAuth(user), Unit))
            assertEquals("OtherUser ${otherUser._id.raw}", Server.maybeWhoAmI.test(OtherUser.testAuth(otherUser), Unit))
        }
    }

    @Test
    fun `model permissions branch on the principal type`() = runBlocking {
        Server.test({}) {
            val userAuth = User.testAuth(User())
            val otherUserAuth = OtherUser.testAuth(OtherUser())
            val model = TestModel()

            assertFailsWith<ForbiddenException> { Server.rest.insert.test(otherUserAuth, model) }
            assertFailsWith<ForbiddenException> { Server.rest.insert.test(null, model) }
            assertEquals(model, Server.rest.insert.test(userAuth, model))

            assertEquals(model, Server.rest.detail.test(model._id, otherUserAuth, Unit))
            assertFailsWith<NotFoundException> { Server.rest.detail.test(model._id, null, Unit) }
        }
    }
}
