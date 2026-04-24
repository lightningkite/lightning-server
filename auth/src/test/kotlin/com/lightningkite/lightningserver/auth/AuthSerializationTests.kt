// by Claude
package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.services.database.HasId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.uuid.Uuid

@Serializable
data class UuidUser(override val _id: Uuid) : HasId<Uuid> {
    companion object : PrincipalType<UuidUser, Uuid> {
        override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
        override val subjectSerializer: KSerializer<UuidUser> = serializer()

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): UuidUser = UuidUser(id)
    }
}

@Serializable
data class IntUser(override val _id: Int) : HasId<Int> {
    companion object : PrincipalType<IntUser, Int> {
        override val idSerializer: KSerializer<Int> = Int.serializer()
        override val subjectSerializer: KSerializer<IntUser> = serializer()

        context(server: ServerRuntime)
        override suspend fun fetch(id: Int): IntUser = IntUser(id)
    }
}

@Serializable
data class StringUser(override val _id: String) : HasId<String> {
    companion object : PrincipalType<StringUser, String> {
        override val idSerializer: KSerializer<String> = String.serializer()
        override val subjectSerializer: KSerializer<StringUser> = serializer()

        context(server: ServerRuntime)
        override suspend fun fetch(id: String): StringUser = StringUser(id)
    }
}

/**
 * Tests for authentication serialization and scope functionality.
 */
class AuthSerializationTests {

    object TestServer : ServerBuilder() {
        init {
            register(UuidUser)
            register(IntUser)
            register(StringUser)
        }
    }

    @Test
    fun `UUID ID is serialized to string format`() = runBlocking {
        TestServer.test({}) {
            val id = Uuid.random()
            val auth = Authentication(
                principalType = UuidUser,
                id = id,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            // The rawId should be serializable
            assertEquals(id, auth.id)
            assertTrue(auth.rawId.isNotEmpty())
        }
    }

    @Test
    fun `Int ID is serialized to string format`() = runBlocking {
        TestServer.test({}) {
            val id = 12345
            val auth = Authentication(
                principalType = IntUser,
                id = id,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            assertEquals(id, auth.id)
            assertTrue(auth.rawId.isNotEmpty())
        }
    }

    @Test
    fun `String ID is serialized correctly`() = runBlocking {
        TestServer.test({}) {
            val id = "user-abc-123"
            val auth = Authentication(
                principalType = StringUser,
                id = id,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            assertEquals(id, auth.id)
        }
    }

    @Test
    fun `GrantedScope serializes to string`() {
        val scope = GrantedScope("api:read")
        val json = Json.encodeToString(scope)

        // Should serialize as a simple string
        assertEquals("\"api:read\"", json)

        // Should deserialize back
        val decoded = Json.decodeFromString<GrantedScope>(json)
        assertEquals(scope, decoded)
    }

    @Test
    fun `GrantedScope root serializes correctly`() {
        val root = GrantedScope.root
        val json = Json.encodeToString(root)

        assertEquals("\"*\"", json)

        val decoded = Json.decodeFromString<GrantedScope>(json)
        assertEquals(root, decoded)
    }

    @Test
    fun `RequiredScope serializes to string`() {
        val scope = RequiredScope("api:write")
        val json = Json.encodeToString(scope)

        assertEquals("\"api:write\"", json)

        val decoded = Json.decodeFromString<RequiredScope>(json)
        assertEquals(scope, decoded)
    }

    @Test
    fun `root scope meets all non-root requirements`() {
        val rootGrant = setOf(GrantedScope.root)

        assertTrue(rootGrant.meetsRequirements(setOf(RequiredScope("api:read"))))
        assertTrue(rootGrant.meetsRequirements(setOf(RequiredScope("api:write"))))
        assertTrue(rootGrant.meetsRequirements(setOf(RequiredScope("admin:users"))))
        assertTrue(rootGrant.meetsRequirements(setOf(RequiredScope("deeply:nested:scope:path"))))
    }

    @Test
    fun `specific scope meets matching requirements`() {
        val grantedScopes = setOf(GrantedScope("api:read"))

        assertTrue(grantedScopes.meetsRequirements(setOf(RequiredScope("api:read"))))
    }

    @Test
    fun `specific scope does not meet different requirements`() {
        val grantedScopes = setOf(GrantedScope("api:read"))

        assertFalse(grantedScopes.meetsRequirements(setOf(RequiredScope("api:write"))))
        assertFalse(grantedScopes.meetsRequirements(setOf(RequiredScope("admin:users"))))
    }

    @Test
    fun `parent scope meets child requirements`() {
        val grantedScopes = setOf(GrantedScope("api"))

        assertTrue(grantedScopes.meetsRequirements(setOf(RequiredScope("api:read"))))
        assertTrue(grantedScopes.meetsRequirements(setOf(RequiredScope("api:write"))))
        assertTrue(grantedScopes.meetsRequirements(setOf(RequiredScope("api:read:users"))))
    }

    @Test
    fun `child scope does not meet parent requirements`() {
        val grantedScopes = setOf(GrantedScope("api:read"))

        // Child should NOT meet parent requirement
        assertFalse(grantedScopes.meetsRequirements(setOf(RequiredScope("api"))))
    }

    @Test
    fun `multiple scopes combine correctly`() {
        val grantedScopes = setOf(
            GrantedScope("api:read"),
            GrantedScope("api:write"),
            GrantedScope("admin")
        )

        assertTrue(grantedScopes.meetsRequirements(setOf(RequiredScope("api:read"))))
        assertTrue(grantedScopes.meetsRequirements(setOf(RequiredScope("api:write"))))
        assertTrue(grantedScopes.meetsRequirements(setOf(RequiredScope("admin"))))
        assertTrue(grantedScopes.meetsRequirements(setOf(RequiredScope("admin:users"))))
        assertFalse(grantedScopes.meetsRequirements(setOf(RequiredScope("api:delete"))))
    }

    @Test
    fun `authentication preserves session ID`() = runBlocking {
        TestServer.test({}) {
            val sessionId = "session-xyz-456"
            val auth = Authentication(
                principalType = UuidUser,
                id = Uuid.random(),
                sessionId = sessionId,
                scopes = setOf(GrantedScope.root)
            )

            assertEquals(sessionId, auth.sessionId)
        }
    }

    @Test
    fun `authentication scopes are preserved`() = runBlocking {
        TestServer.test({}) {
            val scopes = setOf(
                GrantedScope("api:read"),
                GrantedScope("api:write")
            )
            val auth = Authentication(
                principalType = UuidUser,
                id = Uuid.random(),
                sessionId = null,
                scopes = scopes
            )

            assertEquals(scopes, auth.scopes)
        }
    }

    @Test
    fun `authentication principal name is correct`() = runBlocking {
        TestServer.test({}) {
            val auth = Authentication(
                principalType = UuidUser,
                id = Uuid.random(),
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            assertEquals("UuidUser", auth.principalName)
        }
    }

    @Test
    fun `different principal types have different names`() = runBlocking {
        TestServer.test({}) {
            val uuidAuth = Authentication(
                principalType = UuidUser,
                id = Uuid.random(),
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            val intAuth = Authentication(
                principalType = IntUser,
                id = 123,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            assertNotEquals(uuidAuth.principalName, intAuth.principalName)
        }
    }

    @Test
    fun `authentication copy preserves identity`() = runBlocking {
        TestServer.test({}) {
            val originalAuth = Authentication(
                principalType = UuidUser,
                id = Uuid.random(),
                sessionId = "session-123",
                scopes = setOf(GrantedScope("api:read"))
            )

            val newScopes = setOf(GrantedScope("api:write"))
            val copiedAuth = originalAuth.copy(
                scopes = newScopes
            )

            assertEquals(originalAuth.principalName, copiedAuth.principalName)
            assertEquals(originalAuth.rawId, copiedAuth.rawId)
            assertEquals(originalAuth.sessionId, copiedAuth.sessionId)
            assertEquals(newScopes, copiedAuth.scopes)
        }
    }

    @Test
    fun `empty scopes set is preserved`() = runBlocking {
        TestServer.test({}) {
            val auth = Authentication(
                principalType = UuidUser,
                id = Uuid.random(),
                sessionId = null,
                scopes = emptySet()
            )

            assertEquals(emptySet<GrantedScope>(), auth.scopes)
        }
    }

    @Test
    fun `scope with colon is handled correctly`() {
        val scope = GrantedScope("api:read:users:profile")
        assertEquals("api:read:users:profile", scope.asString)

        // Parent scopes should grant access
        val parentScopes = setOf(GrantedScope("api:read:users"))
        assertTrue(parentScopes.meetsRequirements(setOf(RequiredScope("api:read:users:profile"))))
    }

    @Test
    fun `single GrantedScope meetsRequirements`() {
        // Test the single-scope version of meetsRequirements
        val apiScope = GrantedScope("api")
        val readScope = GrantedScope("api:read")

        assertTrue(apiScope.meetsRequirements(RequiredScope("api:read")))
        assertTrue(readScope.meetsRequirements(RequiredScope("api:read")))
        assertFalse(readScope.meetsRequirements(RequiredScope("api:write")))
    }
}
