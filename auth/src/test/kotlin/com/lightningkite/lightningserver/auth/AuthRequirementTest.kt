// by Claude
package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.services.database.HasId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * Tests for AuthRequirement classes and related extension functions.
 */
class AuthRequirementTest {

    @Serializable
    data class TestUser(
        override val _id: Uuid = Uuid.random(),
        val email: String = "",
        val isVerified: Boolean = false
    ) : HasId<Uuid> {
        companion object : PrincipalType<TestUser, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<TestUser> = serializer()

            val store = mutableMapOf<Uuid, TestUser>()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): TestUser = store[id] ?: TestUser(id)
        }
    }

    @Serializable
    data class AdminUser(
        override val _id: Uuid = Uuid.random(),
        val name: String = ""
    ) : HasId<Uuid> {
        companion object : PrincipalType<AdminUser, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<AdminUser> = serializer()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): AdminUser = AdminUser(id)
        }
    }

    object TestServer : ServerBuilder() {
        init {
            register(TestUser)
            register(AdminUser)
        }
    }

    // ========== AuthRequirement.None Tests ==========

    @Test
    fun `None accepts null auth`() = runBlocking {
        TestServer.test({}) {
            val result = noAuth.check(null)
            assertTrue(result is AuthRequirement.Result.Accepted)
            assertNull((result as AuthRequirement.Result.Accepted).auth)
        }
    }

    @Test
    fun `None accepts any auth`() = runBlocking {
        TestServer.test({}) {
            val auth = TestUser.testAuth(TestUser())
            val result = noAuth.check(auth)
            assertTrue(result is AuthRequirement.Result.Accepted)
        }
    }

    @Test
    fun `None subscope returns itself`() {
        val subscoped = noAuth.subscope(Subscope("test"))
        assertEquals(noAuth, subscoped)
    }

    @Test
    fun `None toString is descriptive`() {
        assertEquals("No Requirements", noAuth.toString())
    }

    // ========== AuthRequirement.Authenticated Tests ==========

    @Test
    fun `Authenticated rejects null auth`() = runBlocking {
        TestServer.test({}) {
            val req = anyAuth
            val result = req.check(null)
            assertTrue(result is AuthRequirement.Result.Rejected)
            assertTrue((result as AuthRequirement.Result.Rejected).reason.contains("required"))
        }
    }

    @Test
    fun `Authenticated accepts valid auth`() = runBlocking {
        TestServer.test({}) {
            val auth = TestUser.testAuth(TestUser())
            val result = anyAuth.check(auth)
            assertTrue(result is AuthRequirement.Result.Accepted)
        }
    }

    @Test
    fun `Authenticated checks scopes`() = runBlocking {
        TestServer.test({}) {
            val req = AuthRequirement.Authenticated(scopes = setOf(RequiredScope("admin")))
            val auth = TestUser.testAuth(TestUser(), scopes = setOf(GrantedScope("user")))

            val result = req.check(auth)
            assertTrue(result is AuthRequirement.Result.Rejected)
            assertTrue((result as AuthRequirement.Result.Rejected).reason.contains("scopes"))
        }
    }

    @Test
    fun `Authenticated accepts matching scopes`() = runBlocking {
        TestServer.test({}) {
            val req = AuthRequirement.Authenticated(scopes = setOf(RequiredScope("admin")))
            val auth = TestUser.testAuth(TestUser(), scopes = setOf(GrantedScope("admin")))

            val result = req.check(auth)
            assertTrue(result is AuthRequirement.Result.Accepted)
        }
    }

    @Test
    fun `Authenticated checks maxAge`() = runBlocking {
        TestServer.test({}) {
            val req = AuthRequirement.Authenticated(maxAge = 10.minutes)
            // Create auth that's older than maxAge
            val oldTime = com.lightningkite.lightningserver.runtime.now() - 1.hours
            val auth = Authentication(
                TestUser,
                id = Uuid.random(),
                sessionId = null,
                issuedAt = oldTime,
                scopes = setOf(GrantedScope.root)
            )

            val result = req.check(auth)
            assertTrue(result is AuthRequirement.Result.Rejected)
            assertTrue((result as AuthRequirement.Result.Rejected).reason.contains("max age"))
        }
    }

    @Test
    fun `Authenticated accepts recent auth within maxAge`() = runBlocking {
        TestServer.test({}) {
            val req = AuthRequirement.Authenticated(maxAge = 1.hours)
            val auth = TestUser.testAuth(TestUser())

            val result = req.check(auth)
            assertTrue(result is AuthRequirement.Result.Accepted)
        }
    }

    @Test
    fun `Authenticated checks custom requirement`() = runBlocking {
        TestServer.test({}) {
            val req = AuthRequirement.Authenticated(
                scopes = emptySet(),
                requirement = { false } // Always fails
            )
            val auth = TestUser.testAuth(TestUser())

            val result = req.check(auth)
            assertTrue(result is AuthRequirement.Result.Rejected)
            assertTrue((result as AuthRequirement.Result.Rejected).reason.contains("additional requirement"))
        }
    }

    @Test
    fun `Authenticated accepts when custom requirement passes`() = runBlocking {
        TestServer.test({}) {
            val req = AuthRequirement.Authenticated(
                scopes = emptySet(),
                requirement = { true } // Always passes
            )
            val auth = TestUser.testAuth(TestUser())

            val result = req.check(auth)
            assertTrue(result is AuthRequirement.Result.Accepted)
        }
    }

    @Test
    fun `Authenticated subscope narrows scopes`() {
        val req = AuthRequirement.Authenticated(scopes = setOf(RequiredScope("api")))
        val subscoped = req.subscope(listOf(Subscope("read")))

        assertTrue(subscoped.scopes.any { it.asString.contains("read") })
    }

    @Test
    fun `Authenticated toString is descriptive`() {
        val req = AuthRequirement.Authenticated(
            scopes = setOf(RequiredScope("admin")),
            maxAge = 10.minutes
        )
        val str = req.toString()
        assertTrue(str.contains("Authenticated"))
    }

    // ========== AuthRequirement.AuthenticatedAs Tests ==========

    @Test
    fun `AuthenticatedAs rejects null auth`() = runBlocking {
        TestServer.test({}) {
            val req = TestUser.require()
            val result = req.check(null)
            assertTrue(result is AuthRequirement.Result.Rejected)
        }
    }

    @Test
    fun `AuthenticatedAs rejects wrong principal type`() = runBlocking {
        TestServer.test({}) {
            val req = TestUser.require()
            val auth = AdminUser.testAuth(AdminUser())

            val result = req.check(auth)
            assertTrue(result is AuthRequirement.Result.Rejected)
            assertTrue((result as AuthRequirement.Result.Rejected).reason.contains("not of type"))
        }
    }

    @Test
    fun `AuthenticatedAs accepts correct principal type`() = runBlocking {
        TestServer.test({}) {
            val req = TestUser.require()
            val auth = TestUser.testAuth(TestUser())

            val result = req.check(auth)
            assertTrue(result is AuthRequirement.Result.Accepted)
        }
    }

    @Test
    fun `AuthenticatedAs checks scopes`() = runBlocking {
        TestServer.test({}) {
            val req = TestUser.require(scope = RequiredScope("admin"))
            val auth = TestUser.testAuth(TestUser(), scopes = setOf(GrantedScope("user")))

            val result = req.check(auth)
            assertTrue(result is AuthRequirement.Result.Rejected)
        }
    }

    @Test
    fun `AuthenticatedAs checks maxAge`() = runBlocking {
        TestServer.test({}) {
            val req = TestUser.require(maxAge = 10.minutes)
            val oldTime = com.lightningkite.lightningserver.runtime.now() - 1.hours
            val auth = Authentication(
                TestUser,
                id = Uuid.random(),
                sessionId = null,
                issuedAt = oldTime,
                scopes = setOf(GrantedScope.root)
            )

            val result = req.check(auth)
            assertTrue(result is AuthRequirement.Result.Rejected)
        }
    }

    @Test
    fun `AuthenticatedAs checks custom requirement`() = runBlocking {
        TestServer.test({}) {
            val req = TestUser.require { false }
            val auth = TestUser.testAuth(TestUser())

            val result = req.check(auth)
            assertTrue(result is AuthRequirement.Result.Rejected)
        }
    }

    @Test
    fun `AuthenticatedAs with scopes parameter`() = runBlocking {
        TestServer.test({}) {
            val req = TestUser.require(scopes = setOf(RequiredScope("read"), RequiredScope("write")))
            val auth = TestUser.testAuth(TestUser(), scopes = setOf(GrantedScope("read"), GrantedScope("write")))

            val result = req.check(auth)
            assertTrue(result is AuthRequirement.Result.Accepted)
        }
    }

    @Test
    fun `AuthenticatedAs subscope narrows scopes`() {
        val req = TestUser.require(scope = RequiredScope("api"))
        val subscoped = req.subscope(listOf(Subscope("read")))

        // Verify the subscoped requirement is still an AuthenticatedAs with narrowed scopes
        assertTrue(subscoped is AuthRequirement.AuthenticatedAs<*, *>)
        val typedSubscoped = subscoped as AuthRequirement.AuthenticatedAs<*, *>
        assertTrue(typedSubscoped.scopes.any { it.asString.contains("read") })
    }

    // ========== AuthRequirement.Options Tests ==========

    @Test
    fun `Options accepts if any option accepts`() = runBlocking {
        TestServer.test({}) {
            val req = TestUser.require() or AdminUser.require()
            val auth = TestUser.testAuth(TestUser())

            val result = req.check(auth)
            assertTrue(result is AuthRequirement.Result.Accepted)
        }
    }

    @Test
    fun `Options accepts second option`() = runBlocking {
        TestServer.test({}) {
            val req = TestUser.require() or AdminUser.require()
            val auth = AdminUser.testAuth(AdminUser())

            val result = req.check(auth)
            assertTrue(result is AuthRequirement.Result.Accepted)
        }
    }

    @Test
    fun `Options rejects if all options reject`() = runBlocking {
        TestServer.test({}) {
            val req = TestUser.require() or AdminUser.require()

            val result = req.check(null)
            assertTrue(result is AuthRequirement.Result.Rejected)
        }
    }

    @Test
    fun `Options with noAuth accepts null`() = runBlocking {
        TestServer.test({}) {
            val req = TestUser.require() or noAuth

            val result = req.check(null)
            assertTrue(result is AuthRequirement.Result.Accepted)
        }
    }

    @Test
    fun `Options checks noAuth last`() = runBlocking {
        TestServer.test({}) {
            // noAuth should be checked last, so TestUser.require() is tried first
            val req = noAuth or TestUser.require()
            val auth = TestUser.testAuth(TestUser())

            val result = req.check(auth)
            assertTrue(result is AuthRequirement.Result.Accepted)
            // If noAuth was checked first, it would still return Accepted(null)
            // but with the actual auth, so this should return the TestUser auth
            assertNotNull((result as AuthRequirement.Result.Accepted).auth)
        }
    }

    @Test
    fun `Options subscope applies to all options`() {
        val req = TestUser.require(scope = RequiredScope("api")) or AdminUser.require(scope = RequiredScope("api"))
        val subscoped = req.subscope(listOf(Subscope("read")))

        assertTrue(subscoped is AuthRequirement.Options)
    }

    @Test
    fun `Options toString lists all options`() {
        val req = TestUser.require() or AdminUser.require()
        val str = req.toString()
        assertTrue(str.contains("or"))
    }

    @Test
    fun `options extension flattens nested Options`() {
        val opt1 = TestUser.require() or AdminUser.require()
        val options = opt1.options()
        assertEquals(2, options.size)
    }

    // ========== accepts() and assert() Tests ==========

    @Test
    fun `accepts returns true for accepted auth`() = runBlocking {
        TestServer.test({}) {
            val auth = TestUser.testAuth(TestUser())
            assertTrue(noAuth.accepts(auth))
        }
    }

    @Test
    fun `accepts returns false for rejected auth`() = runBlocking {
        TestServer.test({}) {
            assertFalse(anyAuth.accepts(null))
        }
    }

    @Test
    fun `assert returns auth for accepted`() = runBlocking {
        TestServer.test({}) {
            val user = TestUser()
            val auth = TestUser.testAuth(user)
            val req = TestUser.require()

            val result = req.assert(auth)
            assertNotNull(result)
            assertEquals(auth, result)
        }
    }

    @Test
    fun `assert throws ForbiddenException for rejected`() = runBlocking {
        TestServer.test({}) {
            val req = TestUser.require()

            assertFailsWith<ForbiddenException> {
                req.assert(null)
            }
        }
    }

    @Test
    fun `assert throws with descriptive message`() = runBlocking {
        TestServer.test({}) {
            val req = TestUser.require()

            val exception = assertFailsWith<ForbiddenException> {
                req.assert(null)
            }
            assertTrue(exception.message?.contains("authorization criteria") == true)
        }
    }

    // ========== subscope extension Tests ==========

    @Test
    fun `subscope with single Subscope`() {
        val req = TestUser.require(scope = RequiredScope("api"))
        val subscoped = req.subscope(Subscope("read"))

        assertNotNull(subscoped)
    }

    @Test
    fun `subscope with string`() {
        val req = TestUser.require(scope = RequiredScope("api"))
        val subscoped = req.subscope("read")

        assertNotNull(subscoped)
    }

    // ========== Predefined constants Tests ==========

    @Test
    fun `noAuth constant is None`() {
        assertEquals(AuthRequirement.None, noAuth)
    }

    @Test
    fun `anyAuth constant is Authenticated with empty scopes`() {
        assertTrue(anyAuth is AuthRequirement.Authenticated)
        assertTrue(anyAuth.scopes.isEmpty())
    }

    @Test
    fun `recentRootAuth has root scope and maxAge`() {
        assertTrue(recentRootAuth is AuthRequirement.Authenticated)
        assertTrue(recentRootAuth.scopes.contains(RequiredScope.root))
        assertNotNull(recentRootAuth.maxAge)
    }

    // ========== AuthSetting Tests ==========

    @Test
    fun `IsSuperUser rejects when not configured`() = runBlocking {
        TestServer.test({}) {
            val result = AuthRequirement.IsSuperUser.check(null)
            assertTrue(result is AuthRequirement.Result.Rejected)
        }
    }

    @Test
    fun `IsAdmin falls back to IsSuperUser default`() = runBlocking {
        // IsAdmin has IsSuperUser as default, which is also not configured
        TestServer.test({}) {
            val result = AuthRequirement.IsAdmin.check(null)
            assertTrue(result is AuthRequirement.Result.Rejected)
        }
    }

    @Test
    fun `IsDeveloper falls back to IsSuperUser default`() = runBlocking {
        // IsDeveloper has IsSuperUser as default
        TestServer.test({}) {
            val result = AuthRequirement.IsDeveloper.check(null)
            assertTrue(result is AuthRequirement.Result.Rejected)
        }
    }

    @Test
    fun `AuthSetting Scoped wraps setting with subscopes`() {
        val scoped = AuthRequirement.IsSuperUser.subscope(listOf(Subscope("test")))
        assertTrue(scoped is AuthRequirement.AuthSetting.Scoped)
        assertEquals(AuthRequirement.IsSuperUser, (scoped as AuthRequirement.AuthSetting.Scoped).wraps)
    }

    // ========== naturalLanguage Tests ==========

    @Test
    fun `naturalLanguage for None`() = runBlocking {
        TestServer.test({}) {
            val lang = noAuth.naturalLanguage()
            assertEquals("No Requirements", lang)
        }
    }

    @Test
    fun `naturalLanguage for Options`() = runBlocking {
        TestServer.test({}) {
            val req = TestUser.require() or noAuth
            val lang = req.naturalLanguage()
            assertTrue(lang.contains("or"))
        }
    }

    @Test
    fun `naturalLanguage with markdown`() = runBlocking {
        TestServer.test({}) {
            val req = TestUser.require() or noAuth
            val lang = req.naturalLanguage(markdown = true)
            assertTrue(lang.contains("*or*"))
        }
    }

    // ========== requiredScopes Tests ==========

    @Test
    fun `None requiredScopes is empty`() = runBlocking {
        TestServer.test({}) {
            val scopes = noAuth.requiredScopes()
            assertTrue(scopes.isEmpty())
        }
    }

    @Test
    fun `Authenticated requiredScopes matches scopes`() = runBlocking {
        TestServer.test({}) {
            val req = AuthRequirement.Authenticated(scopes = setOf(RequiredScope("admin")))
            val scopes = req.requiredScopes()
            assertTrue(scopes.contains(RequiredScope("admin")))
        }
    }

    @Test
    fun `AuthenticatedAs requiredScopes matches scopes`() = runBlocking {
        TestServer.test({}) {
            val req = TestUser.require(scope = RequiredScope("read"))
            val scopes = req.requiredScopes()
            assertTrue(scopes.contains(RequiredScope("read")))
        }
    }

    @Test
    fun `Options requiredScopes combines all options`() = runBlocking {
        TestServer.test({}) {
            val req = TestUser.require(scope = RequiredScope("read")) or
                      AdminUser.require(scope = RequiredScope("admin"))
            val scopes = req.requiredScopes()
            assertTrue(scopes.contains(RequiredScope("read")))
            assertTrue(scopes.contains(RequiredScope("admin")))
        }
    }

    // ========== Role-based Requirements Tests ==========

    @Test
    fun `isSuperUser returns IsSuperUser`() = runBlocking {
        TestServer.test({}) {
            val req = AuthRequirement.isSuperUser
            assertEquals(AuthRequirement.IsSuperUser, req)
        }
    }

    @Test
    fun `isAdmin returns IsAdmin`() = runBlocking {
        TestServer.test({}) {
            val req = AuthRequirement.isAdmin
            assertEquals(AuthRequirement.IsAdmin, req)
        }
    }

    @Test
    fun `isDeveloper returns IsDeveloper`() = runBlocking {
        TestServer.test({}) {
            val req = AuthRequirement.isDeveloper
            assertEquals(AuthRequirement.IsDeveloper, req)
        }
    }

    // ========== ServerBuilder Context Setter Tests ==========
    // by Claude

    @Test
    fun `isSuperUser setter in ServerBuilder context`() = runBlocking {
        // Create a custom server builder and set a custom isSuperUser
        val customRequirement = TestUser.require(scope = RequiredScope("super"))

        object : ServerBuilder() {
            init {
                register(TestUser)
                AuthRequirement.isSuperUser = customRequirement
            }
        }.test({}) {
            // Verify the setting was applied through the AuthSetting mechanism
            val settingValue = AuthRequirement.IsSuperUser.setting()
            assertEquals(customRequirement, settingValue)
        }
    }

    @Test
    fun `isAdmin setter in ServerBuilder context`() = runBlocking {
        val customRequirement = TestUser.require(scope = RequiredScope("admin-custom"))

        object : ServerBuilder() {
            init {
                register(TestUser)
                AuthRequirement.isAdmin = customRequirement
            }
        }.test({}) {
            val settingValue = AuthRequirement.IsAdmin.setting()
            assertEquals(customRequirement, settingValue)
        }
    }

    @Test
    fun `isDeveloper setter in ServerBuilder context`() = runBlocking {
        val customRequirement = TestUser.require(scope = RequiredScope("dev-custom"))

        object : ServerBuilder() {
            init {
                register(TestUser)
                AuthRequirement.isDeveloper = customRequirement
            }
        }.test({}) {
            val settingValue = AuthRequirement.IsDeveloper.setting()
            assertEquals(customRequirement, settingValue)
        }
    }

    @Test
    fun `configured isSuperUser is used in check`() = runBlocking {
        val user = TestUser(isVerified = true)
        TestUser.store[user._id] = user

        val customRequirement = TestUser.require()

        object : ServerBuilder() {
            init {
                register(TestUser)
                AuthRequirement.isSuperUser = customRequirement
            }
        }.test({}) {
            val auth = TestUser.testAuth(user)
            val result = AuthRequirement.IsSuperUser.check(auth)
            assertTrue(result is AuthRequirement.Result.Accepted)
        }

        TestUser.store.clear()
    }

    @Test
    fun `configured isAdmin is used in check`() = runBlocking {
        val user = TestUser(isVerified = true)
        TestUser.store[user._id] = user

        val customRequirement = TestUser.require()

        object : ServerBuilder() {
            init {
                register(TestUser)
                AuthRequirement.isAdmin = customRequirement
            }
        }.test({}) {
            val auth = TestUser.testAuth(user)
            val result = AuthRequirement.IsAdmin.check(auth)
            assertTrue(result is AuthRequirement.Result.Accepted)
        }

        TestUser.store.clear()
    }

    @Test
    fun `configured isDeveloper is used in check`() = runBlocking {
        val user = TestUser(isVerified = true)
        TestUser.store[user._id] = user

        val customRequirement = TestUser.require()

        object : ServerBuilder() {
            init {
                register(TestUser)
                AuthRequirement.isDeveloper = customRequirement
            }
        }.test({}) {
            val auth = TestUser.testAuth(user)
            val result = AuthRequirement.IsDeveloper.check(auth)
            assertTrue(result is AuthRequirement.Result.Accepted)
        }

        TestUser.store.clear()
    }

    // ========== naturalLanguage for AuthSetting Tests ==========
    // by Claude

    @Test
    fun `naturalLanguage for AuthSetting shows resolved value`() = runBlocking {
        val customRequirement = TestUser.require(scope = RequiredScope("custom"))

        object : ServerBuilder() {
            init {
                register(TestUser)
                AuthRequirement.isSuperUser = customRequirement
            }
        }.test({}) {
            val lang = AuthRequirement.IsSuperUser.naturalLanguage()
            assertTrue(lang.contains("IsSuperUser"))
        }
    }

    @Test
    fun `naturalLanguage for unconfigured AuthSetting`() = runBlocking {
        TestServer.test({}) {
            // IsSuperUser is not configured in TestServer
            val lang = AuthRequirement.IsSuperUser.naturalLanguage()
            assertTrue(lang.contains("IsSuperUser"))
        }
    }
}
