// by Claude
package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.data.SerializableCache
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * Tests for Authentication extension functions and related utilities.
 */
class AuthenticationExtTest {

    @Serializable
    data class AuthUser(
        override val _id: Uuid = Uuid.random(),
        val email: String = "",
        val displayName: String = ""
    ) : HasId<Uuid> {
        companion object : PrincipalType<AuthUser, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<AuthUser> = serializer()

            val store = mutableMapOf<Uuid, AuthUser>()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): AuthUser = store[id] ?: AuthUser(id)
        }
    }

    object TestServer : ServerBuilder() {
        init {
            register(AuthUser)
        }
    }

    // ========== testAuth Tests ==========

    @Test
    fun `testAuth creates authentication with subject`() = runBlocking {
        TestServer.test({}) {
            val user = AuthUser(email = "test@example.com")
            val auth = AuthUser.testAuth(user)

            assertNotNull(auth)
            assertEquals(user._id, auth.id)
        }
    }

    @Test
    fun `testAuth defaults to root scope`() = runBlocking {
        TestServer.test({}) {
            val user = AuthUser()
            val auth = AuthUser.testAuth(user)

            assertTrue(auth.scopes.contains(GrantedScope.root))
        }
    }

    @Test
    fun `testAuth accepts custom scopes`() = runBlocking {
        TestServer.test({}) {
            val user = AuthUser()
            val customScopes = setOf(GrantedScope("api:read"), GrantedScope("api:write"))
            val auth = AuthUser.testAuth(user, scopes = customScopes)

            assertEquals(customScopes, auth.scopes)
        }
    }

    @Test
    fun `testAuth uses current time for issuedAt`() = runBlocking {
        TestServer.test({}) {
            val user = AuthUser()
            val before = com.lightningkite.lightningserver.runtime.now()
            val auth = AuthUser.testAuth(user)
            val after = com.lightningkite.lightningserver.runtime.now()

            assertTrue(auth.issuedAt >= before)
            assertTrue(auth.issuedAt <= after)
        }
    }

    @Test
    fun `testAuth accepts custom issuedAt`() = runBlocking {
        TestServer.test({}) {
            val user = AuthUser()
            val customTime = com.lightningkite.lightningserver.runtime.now() - 1.hours
            val auth = AuthUser.testAuth(user, issuedAt = customTime)

            assertEquals(customTime, auth.issuedAt)
        }
    }

    // ========== meetsRequirements Tests ==========

    @Test
    fun `meetsRequirements returns true for matching scopes`() = runBlocking {
        TestServer.test({}) {
            val auth = AuthUser.testAuth(AuthUser(), scopes = setOf(GrantedScope("admin")))

            assertTrue(auth.meetsRequirements(setOf(RequiredScope("admin"))))
        }
    }

    @Test
    fun `meetsRequirements returns true for subscope`() = runBlocking {
        TestServer.test({}) {
            val auth = AuthUser.testAuth(AuthUser(), scopes = setOf(GrantedScope("admin")))

            assertTrue(auth.meetsRequirements(setOf(RequiredScope("admin:read"))))
        }
    }

    @Test
    fun `meetsRequirements returns false for missing scopes`() = runBlocking {
        TestServer.test({}) {
            val auth = AuthUser.testAuth(AuthUser(), scopes = setOf(GrantedScope("user")))

            assertFalse(auth.meetsRequirements(setOf(RequiredScope("admin"))))
        }
    }

    @Test
    fun `meetsRequirements returns true for root scope`() = runBlocking {
        TestServer.test({}) {
            val auth = AuthUser.testAuth(AuthUser(), scopes = setOf(GrantedScope.root))

            assertTrue(auth.meetsRequirements(setOf(RequiredScope("anything"))))
        }
    }

    @Test
    fun `meetsRequirements returns true for empty required scopes`() = runBlocking {
        TestServer.test({}) {
            val auth = AuthUser.testAuth(AuthUser(), scopes = setOf(GrantedScope("user")))

            assertTrue(auth.meetsRequirements(emptySet()))
        }
    }

    // ========== Authentication properties Tests ==========

    @Test
    fun `principalType returns correct type`() = runBlocking {
        TestServer.test({}) {
            val user = AuthUser()
            val auth = AuthUser.testAuth(user)

            assertEquals(AuthUser, auth.principalType)
        }
    }

    @Test
    fun `id returns correct id`() = runBlocking {
        TestServer.test({}) {
            val user = AuthUser(Uuid.parse("550e8400-e29b-41d4-a716-446655440000"))
            val auth = AuthUser.testAuth(user)

            assertEquals(user._id, auth.id)
        }
    }

    @Test
    fun `fetch returns subject`() = runBlocking {
        AuthUser.store.clear()
        val id = Uuid.random()
        val user = AuthUser(id, "user@example.com", "Test User")
        AuthUser.store[id] = user

        TestServer.test({}) {
            val auth = AuthUser.testAuth(user)
            val fetched = auth.fetch()

            assertEquals(user, fetched)
            assertEquals("user@example.com", fetched.email)
        }
    }

    @Test
    fun `fetch caches result`() = runBlocking {
        AuthUser.store.clear()
        val id = Uuid.random()
        val user = AuthUser(id, "cached@example.com", "Cached User")
        AuthUser.store[id] = user

        TestServer.test({}) {
            val auth = AuthUser.testAuth(user)

            // First fetch
            val fetched1 = auth.fetch()
            assertEquals(user, fetched1)

            // Modify the store
            AuthUser.store[id] = AuthUser(id, "modified@example.com", "Modified User")

            // Second fetch should return cached value
            val fetched2 = auth.fetch()
            assertEquals("cached@example.com", fetched2.email) // Still cached
        }
    }

    // ========== Authentication constructor Tests ==========

    @Test
    fun `Authentication constructor with subject sets up cache`() = runBlocking {
        TestServer.test({}) {
            val user = AuthUser(email = "test@example.com")
            val auth = Authentication(
                principalType = AuthUser,
                subject = user,
                sessionId = "test-session"
            )

            // The subject should be pre-cached
            val fetched = auth.fetch()
            assertEquals(user, fetched)
        }
    }

    @Test
    fun `Authentication constructor with subject and custom scopes`() = runBlocking {
        TestServer.test({}) {
            val user = AuthUser()
            val customScopes = setOf(GrantedScope("read"), GrantedScope("write"))
            val auth = Authentication(
                principalType = AuthUser,
                subject = user,
                sessionId = null,
                scopes = customScopes
            )

            assertEquals(customScopes, auth.scopes)
        }
    }

    @Test
    fun `Authentication constructor with subject and expiration`() = runBlocking {
        TestServer.test({}) {
            val user = AuthUser()
            val expiration = com.lightningkite.lightningserver.runtime.now() + 1.hours
            val auth = Authentication(
                principalType = AuthUser,
                subject = user,
                sessionId = null,
                expiration = expiration
            )

            assertEquals(expiration, auth.expiration)
        }
    }

    // ========== Authentication copy Tests ==========

    @Test
    fun `copy preserves most fields`() = runBlocking {
        TestServer.test({}) {
            val user = AuthUser()
            val auth = AuthUser.testAuth(user)

            val copied = auth.copy()

            assertEquals(auth.principalName, copied.principalName)
            assertEquals(auth.rawId, copied.rawId)
            assertEquals(auth.sessionId, copied.sessionId)
            assertEquals(auth.issuedAt, copied.issuedAt)
        }
    }

    @Test
    fun `copy allows changing expiration`() = runBlocking {
        TestServer.test({}) {
            val user = AuthUser()
            val auth = AuthUser.testAuth(user)
            val newExpiration = com.lightningkite.lightningserver.runtime.now() + 2.hours

            val copied = auth.copy(expiration = newExpiration)

            assertEquals(newExpiration, copied.expiration)
        }
    }

    @Test
    fun `copy allows changing scopes`() = runBlocking {
        TestServer.test({}) {
            val user = AuthUser()
            val auth = AuthUser.testAuth(user)
            val newScopes = setOf(GrantedScope("limited"))

            val copied = auth.copy(scopes = newScopes)

            assertEquals(newScopes, copied.scopes)
        }
    }

    // ========== Authentication toString Tests ==========

    @Test
    fun `toString includes principal name`() = runBlocking {
        TestServer.test({}) {
            val user = AuthUser()
            val auth = AuthUser.testAuth(user)

            assertTrue(auth.toString().contains("AuthUser"))
        }
    }

    // ========== Authentication with masquerade Tests ==========

    @Test
    fun `Authentication with fromMasquerade preserves original auth`() = runBlocking {
        TestServer.test({}) {
            val adminUser = AuthUser(email = "admin@example.com")
            val adminAuth = AuthUser.testAuth(adminUser)

            val targetUser = AuthUser(email = "target@example.com")
            val masqueradeAuth = Authentication(
                principalType = AuthUser,
                subject = targetUser,
                sessionId = null,
                fromMasquerade = adminAuth
            )

            assertNotNull(masqueradeAuth.fromMasquerade)
            assertEquals(adminAuth, masqueradeAuth.fromMasquerade)
        }
    }

    @Test
    fun `toString includes masquerade info`() = runBlocking {
        TestServer.test({}) {
            val adminUser = AuthUser(email = "admin@example.com")
            val adminAuth = AuthUser.testAuth(adminUser)

            val targetUser = AuthUser(email = "target@example.com")
            val masqueradeAuth = Authentication(
                principalType = AuthUser,
                subject = targetUser,
                sessionId = null,
                fromMasquerade = adminAuth
            )

            assertTrue(masqueradeAuth.toString().contains("masquerading"))
        }
    }

    // ========== authReaders Tests ==========

    @Test
    fun `authReaders list is accessible`() = runBlocking {
        TestServer.test({}) {
            // Access authReaders on the ServerBuilder (this)
            val readers = authReaders
            assertNotNull(readers)
        }
    }

    // ========== SerializableCache integration Tests ==========

    @Test
    fun `Authentication cache stores values`() = runBlocking {
        TestServer.test({}) {
            val user = AuthUser()
            val cache = SerializableCache()
            val auth = Authentication(
                principalType = AuthUser,
                id = user._id,
                sessionId = null,
                cache = cache
            )

            assertNotNull(auth.cache)
        }
    }

    // ========== untypedId and untypedPrincipal Tests ==========

    @Test
    fun `untypedId returns correct id`() = runBlocking {
        TestServer.test({}) {
            val user = AuthUser(Uuid.parse("550e8400-e29b-41d4-a716-446655440000"))
            val auth = AuthUser.testAuth(user)

            val untypedId = auth.untypedId
            assertEquals(user._id, untypedId)
        }
    }

    @Test
    fun `untypedPrincipal returns correct type`() = runBlocking {
        TestServer.test({}) {
            val user = AuthUser()
            val auth = AuthUser.testAuth(user)

            val untypedPrincipal = auth.untypedPrincipal
            assertEquals(AuthUser, untypedPrincipal)
        }
    }

    // ========== rawId Tests ==========

    @Test
    fun `rawId is serialized form of id`() = runBlocking {
        TestServer.test({}) {
            val id = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
            val user = AuthUser(id)
            val auth = AuthUser.testAuth(user)

            assertNotNull(auth.rawId)
            assertTrue(auth.rawId.isNotEmpty())
        }
    }

    // ========== sessionId Tests ==========

    @Test
    fun `sessionId is null when not provided`() = runBlocking {
        TestServer.test({}) {
            val user = AuthUser()
            val auth = AuthUser.testAuth(user)

            // testAuth creates auth without sessionId
            assertEquals(null, auth.sessionId)
        }
    }

    @Test
    fun `sessionId is preserved when provided`() = runBlocking {
        TestServer.test({}) {
            val user = AuthUser()
            val auth = Authentication(
                principalType = AuthUser,
                id = user._id,
                sessionId = "test-session-123"
            )

            assertEquals("test-session-123", auth.sessionId)
        }
    }

    // ========== principalName Tests ==========

    @Test
    fun `principalName matches principal type name`() = runBlocking {
        TestServer.test({}) {
            val user = AuthUser()
            val auth = AuthUser.testAuth(user)

            assertEquals(AuthUser.name, auth.principalName)
        }
    }

    // ========== Multiple scope tests ==========

    @Test
    fun `meetsRequirements with multiple required scopes`() = runBlocking {
        TestServer.test({}) {
            val auth = AuthUser.testAuth(
                AuthUser(),
                scopes = setOf(GrantedScope("api:read"), GrantedScope("api:write"))
            )

            // Has both scopes
            assertTrue(auth.meetsRequirements(setOf(RequiredScope("api:read"), RequiredScope("api:write"))))
        }
    }

    @Test
    fun `meetsRequirements fails when missing one of multiple required scopes`() = runBlocking {
        TestServer.test({}) {
            val auth = AuthUser.testAuth(
                AuthUser(),
                scopes = setOf(GrantedScope("api:read"))
            )

            // Missing api:write
            assertFalse(auth.meetsRequirements(setOf(RequiredScope("api:read"), RequiredScope("api:write"))))
        }
    }

    // ========== Expiration Tests ==========

    @Test
    fun `expiration is null by default`() = runBlocking {
        TestServer.test({}) {
            val user = AuthUser()
            val auth = AuthUser.testAuth(user)

            assertEquals(null, auth.expiration)
        }
    }

    // ========== AuthCacheKey get() Extension Tests ========== (by Claude)

    @Test
    fun `get with AuthCacheKey calculates and caches value`() = runBlocking {
        var calculateCount = 0

        // Define a custom AuthCacheKey for testing
        val customKey = object : AuthCacheKey<AuthUser, String> {
            override val id: String = "custom-computed-value"
            override val serializer = String.serializer()

            context(server: ServerRuntime)
            override suspend fun calculate(input: Authentication<AuthUser>): String {
                calculateCount++
                return "computed-for-${input.id}"
            }
        }

        TestServer.test({}) {
            val user = AuthUser(email = "test@example.com")
            val auth = AuthUser.testAuth(user)

            // First call should calculate
            val result1 = auth.get(customKey)
            assertEquals("computed-for-${user._id}", result1)
            assertEquals(1, calculateCount)

            // Second call should use cached value
            val result2 = auth.get(customKey)
            assertEquals("computed-for-${user._id}", result2)
            assertEquals(1, calculateCount) // Still 1, not recalculated
        }
    }

    @Test
    fun `get with AuthCacheKey uses input authentication`() = runBlocking {
        val capturedIds = mutableListOf<String>()

        val idCapturingKey = object : AuthCacheKey<AuthUser, String> {
            override val id: String = "id-capturing-key"
            override val serializer = String.serializer()

            context(server: ServerRuntime)
            override suspend fun calculate(input: Authentication<AuthUser>): String {
                capturedIds.add(input.rawId)
                return "captured"
            }
        }

        TestServer.test({}) {
            val user1 = AuthUser(email = "user1@example.com")
            val auth1 = AuthUser.testAuth(user1)
            auth1.get(idCapturingKey)

            val user2 = AuthUser(email = "user2@example.com")
            val auth2 = AuthUser.testAuth(user2)
            auth2.get(idCapturingKey)

            assertEquals(2, capturedIds.size)
            assertTrue(capturedIds[0] != capturedIds[1])
        }
    }

    // ========== Edge Cases ==========

    @Test
    fun `toString with all fields populated`() = runBlocking {
        TestServer.test({}) {
            val adminUser = AuthUser(email = "admin@example.com")
            val adminAuth = AuthUser.testAuth(adminUser)

            val targetUser = AuthUser(email = "target@example.com")
            val expiration = com.lightningkite.lightningserver.runtime.now() + 1.hours
            val masqueradeAuth = Authentication(
                principalType = AuthUser,
                subject = targetUser,
                sessionId = "session-123",
                expiration = expiration,
                scopes = setOf(GrantedScope("api:read")),
                fromMasquerade = adminAuth
            )

            val str = masqueradeAuth.toString()
            assertTrue(str.contains("AuthUser"))
            assertTrue(str.contains("masquerading"))
        }
    }
}
