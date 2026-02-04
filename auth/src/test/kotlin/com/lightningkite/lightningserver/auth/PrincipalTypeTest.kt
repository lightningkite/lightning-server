// by Claude
package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.definition.builder.DuplicateRegistrationError
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.services.database.HasId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Tests for PrincipalType - core authentication principal functionality.
 */
class PrincipalTypeTest {

    @Serializable
    data class TestPrincipal(
        override val _id: Uuid = Uuid.random(),
        val email: String = "",
        val phone: String = "",
        val displayName: String = ""
    ) : HasId<Uuid> {
        companion object : PrincipalType<TestPrincipal, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<TestPrincipal> = serializer()

            val store = mutableMapOf<Uuid, TestPrincipal>()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): TestPrincipal =
                store[id] ?: throw IllegalArgumentException("Principal not found: $id")

            override fun normalizePropertyValue(property: String, value: String): String {
                return when (property) {
                    "email" -> value.lowercase().trim()
                    "phone" -> value.filter { it.isDigit() }
                    else -> value
                }
            }

            context(server: ServerRuntime)
            override suspend fun fetchByProperty(property: String, value: String): TestPrincipal? {
                val normalized = normalizePropertyValue(property, value)
                return when (property) {
                    "email" -> store.values.find { it.email.lowercase() == normalized }
                    "phone" -> store.values.find { it.phone.filter { c -> c.isDigit() } == normalized }
                    else -> super.fetchByProperty(property, value)
                }
            }
        }
    }

    @Serializable
    data class IntIdPrincipal(
        override val _id: Int = 0,
        val name: String = ""
    ) : HasId<Int> {
        companion object : PrincipalType<IntIdPrincipal, Int> {
            override val idSerializer: KSerializer<Int> = Int.serializer()
            override val subjectSerializer: KSerializer<IntIdPrincipal> = serializer()

            val store = mutableMapOf<Int, IntIdPrincipal>()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Int): IntIdPrincipal =
                store[id] ?: throw IllegalArgumentException("Principal not found: $id")
        }
    }

    @Serializable
    data class StringIdPrincipal(
        override val _id: String = "",
        val label: String = ""
    ) : HasId<String> {
        companion object : PrincipalType<StringIdPrincipal, String> {
            override val idSerializer: KSerializer<String> = String.serializer()
            override val subjectSerializer: KSerializer<StringIdPrincipal> = serializer()

            val store = mutableMapOf<String, StringIdPrincipal>()

            context(server: ServerRuntime)
            override suspend fun fetch(id: String): StringIdPrincipal =
                store[id] ?: throw IllegalArgumentException("Principal not found: $id")
        }
    }

    @Test
    fun `name returns simple class name`() {
        assertEquals("TestPrincipal", TestPrincipal.name)
        assertEquals("IntIdPrincipal", IntIdPrincipal.name)
        assertEquals("StringIdPrincipal", StringIdPrincipal.name)
    }

    @Test
    fun `fetch returns stored principal`() = runBlocking {
        TestPrincipal.store.clear()
        val id = Uuid.random()
        val principal = TestPrincipal(id, "test@example.com", "555-1234", "Test User")
        TestPrincipal.store[id] = principal

        object : ServerBuilder() {
            init {
                register(TestPrincipal)
            }
        }.test({}) {
            val fetched = TestPrincipal.fetch(id)
            assertEquals(principal, fetched)
            assertEquals("test@example.com", fetched.email)
            assertEquals("Test User", fetched.displayName)
        }
    }

    @Test
    fun `fetch throws for nonexistent principal`() = runBlocking {
        TestPrincipal.store.clear()
        val nonexistentId = Uuid.random()

        object : ServerBuilder() {
            init {
                register(TestPrincipal)
            }
        }.test({}) {
            assertFailsWith<IllegalArgumentException>("Should throw for nonexistent ID") {
                TestPrincipal.fetch(nonexistentId)
            }
        }
    }

    @Test
    fun `fetchByProperty finds principal by email`() = runBlocking {
        TestPrincipal.store.clear()
        val id = Uuid.random()
        val principal = TestPrincipal(id, "user@example.com", "555-1234", "User")
        TestPrincipal.store[id] = principal

        object : ServerBuilder() {
            init {
                register(TestPrincipal)
            }
        }.test({}) {
            val fetched = TestPrincipal.fetchByProperty("email", "user@example.com")
            assertNotNull(fetched)
            assertEquals(principal, fetched)
        }
    }

    @Test
    fun `fetchByProperty returns null for nonexistent property value`() = runBlocking {
        TestPrincipal.store.clear()
        val id = Uuid.random()
        val principal = TestPrincipal(id, "user@example.com", "555-1234", "User")
        TestPrincipal.store[id] = principal

        object : ServerBuilder() {
            init {
                register(TestPrincipal)
            }
        }.test({}) {
            val fetched = TestPrincipal.fetchByProperty("email", "nonexistent@example.com")
            assertNull(fetched)
        }
    }

    @Test
    fun `fetchByProperty normalizes values before lookup`() = runBlocking {
        TestPrincipal.store.clear()
        val id = Uuid.random()
        val principal = TestPrincipal(id, "user@example.com", "5551234", "User")
        TestPrincipal.store[id] = principal

        object : ServerBuilder() {
            init {
                register(TestPrincipal)
            }
        }.test({}) {
            // Email should be case-insensitive
            val byUpperEmail = TestPrincipal.fetchByProperty("email", "USER@EXAMPLE.COM")
            assertNotNull(byUpperEmail)
            assertEquals(principal, byUpperEmail)

            // Phone should ignore non-digits
            val byFormattedPhone = TestPrincipal.fetchByProperty("phone", "555-12-34")
            assertNotNull(byFormattedPhone)
            assertEquals(principal, byFormattedPhone)
        }
    }

    @Test
    fun `normalizePropertyValue lowercases email`() {
        assertEquals("test@example.com", TestPrincipal.normalizePropertyValue("email", "TEST@EXAMPLE.COM"))
        assertEquals("test@example.com", TestPrincipal.normalizePropertyValue("email", "Test@Example.Com"))
        assertEquals("test@example.com", TestPrincipal.normalizePropertyValue("email", "  test@example.com  "))
    }

    @Test
    fun `normalizePropertyValue strips non-digits from phone`() {
        assertEquals("5551234567", TestPrincipal.normalizePropertyValue("phone", "555-123-4567"))
        assertEquals("5551234567", TestPrincipal.normalizePropertyValue("phone", "(555) 123-4567"))
        assertEquals("15551234567", TestPrincipal.normalizePropertyValue("phone", "+1 555 123 4567"))
    }

    @Test
    fun `normalizePropertyValue returns value unchanged for unknown properties`() {
        assertEquals("SomeValue", TestPrincipal.normalizePropertyValue("unknownProp", "SomeValue"))
        assertEquals("  spaces  ", TestPrincipal.normalizePropertyValue("other", "  spaces  "))
    }

    @Test
    fun `hasProperty returns true for existing properties`() {
        assertTrue(TestPrincipal.hasProperty("email"))
        assertTrue(TestPrincipal.hasProperty("phone"))
        assertTrue(TestPrincipal.hasProperty("displayName"))
        assertTrue(TestPrincipal.hasProperty("_id"))
    }

    @Test
    fun `hasProperty returns false for nonexistent properties`() {
        assertFalse(TestPrincipal.hasProperty("nonexistent"))
        assertFalse(TestPrincipal.hasProperty("password"))
        assertFalse(TestPrincipal.hasProperty("Email")) // Case sensitive
    }

    @Test
    fun `idString serializes UUID correctly`() = runBlocking {
        object : ServerBuilder() {
            init {
                register(TestPrincipal)
            }
        }.test({}) {
            val id = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
            val idString = TestPrincipal.idString(id)
            assertNotNull(idString)
            assertTrue(idString.contains("550e8400"))
        }
    }

    @Test
    fun `idString serializes Int correctly`() = runBlocking {
        object : ServerBuilder() {
            init {
                register(IntIdPrincipal)
            }
        }.test({}) {
            val idString = IntIdPrincipal.idString(12345)
            assertEquals("12345", idString)
        }
    }

    @Test
    fun `idString serializes String correctly`() = runBlocking {
        object : ServerBuilder() {
            init {
                register(StringIdPrincipal)
            }
        }.test({}) {
            val idString = StringIdPrincipal.idString("my-custom-id")
            // String serialization includes quotes in JSON format
            assertTrue(idString.contains("my-custom-id"))
        }
    }

    @Test
    fun `subjectCacheKey has correct id`() {
        val cacheKey = TestPrincipal.subjectCacheKey
        assertEquals("TestPrincipal-subject", cacheKey.id)
    }

    @Test
    fun `subjectCacheKey uses correct serializer`() {
        val cacheKey = TestPrincipal.subjectCacheKey
        assertEquals(TestPrincipal.subjectSerializer, cacheKey.serializer)
    }

    @Test
    fun `subjectCacheKey is local only`() {
        val cacheKey = TestPrincipal.subjectCacheKey
        assertTrue(cacheKey.localOnly, "Subject cache should be local only")
    }

    @Test
    fun `register does not throw for valid principal type`() {
        // Should not throw when registering a valid principal type
        object : ServerBuilder() {
            init {
                register(TestPrincipal)
            }
        }
    }

    @Test
    fun `register multiple principal types does not throw`() {
        // Should not throw when registering multiple different principal types
        object : ServerBuilder() {
            init {
                register(TestPrincipal)
                register(IntIdPrincipal)
                register(StringIdPrincipal)
            }
        }
    }

    @Test
    fun `register same principal type twice is idempotent`() {
        // Should not throw - registering same type twice is okay
        object : ServerBuilder() {
            init {
                register(TestPrincipal)
                register(TestPrincipal) // Same type again - should not throw
            }
        }
    }

    @Test
    fun `permitMasquerade defaults to false`() = runBlocking {
        TestPrincipal.store.clear()
        val id = Uuid.random()
        val principal = TestPrincipal(id, "user@example.com", "555-1234", "User")
        TestPrincipal.store[id] = principal

        object : ServerBuilder() {
            init {
                register(TestPrincipal)
            }
        }.test({}) {
            val fromAuth = Authentication(
                principalType = TestPrincipal,
                id = id,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )
            val intoAuth = Authentication(
                principalType = TestPrincipal,
                id = Uuid.random(),
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            // Default implementation returns false
            val permitted = TestPrincipal.permitMasquerade(fromAuth, intoAuth)
            assertFalse(permitted, "Default permitMasquerade should return false")
        }
    }

    @Test
    fun `precache defaults to empty list`() {
        assertTrue(TestPrincipal.precache.isEmpty())
        assertTrue(IntIdPrincipal.precache.isEmpty())
    }

    @Test
    fun `getProperty returns ID for TypeName slash _id`() = runBlocking {
        TestPrincipal.store.clear()
        val id = Uuid.random()
        val principal = TestPrincipal(id, "user@example.com", "555-1234", "User")
        TestPrincipal.store[id] = principal

        object : ServerBuilder() {
            init {
                register(TestPrincipal)
            }
        }.test({}) {
            val idProperty = TestPrincipal.getProperty(principal, "TestPrincipal/_id")
            assertNotNull(idProperty)
            // idString returns JSON-encoded UUID which includes the UUID string with dashes
            assertTrue(idProperty.contains(id.toString()), "Should contain the UUID")
        }
    }

    // ========== Default Implementation Tests ==========
    // These test the interface default implementations (PrincipalType$DefaultImpls)

    @Test
    fun `default normalizePropertyValue returns value unchanged`() {
        // IntIdPrincipal uses default normalizePropertyValue
        assertEquals("SomeValue", IntIdPrincipal.normalizePropertyValue("anyProp", "SomeValue"))
        assertEquals("  SPACES  ", IntIdPrincipal.normalizePropertyValue("name", "  SPACES  "))
        assertEquals("Test@Example.COM", IntIdPrincipal.normalizePropertyValue("email", "Test@Example.COM"))
    }

    @Test
    fun `default fetchByProperty returns null for unknown properties`() = runBlocking {
        IntIdPrincipal.store.clear()
        IntIdPrincipal.store[1] = IntIdPrincipal(1, "Test")

        object : ServerBuilder() {
            init {
                register(IntIdPrincipal)
            }
        }.test({}) {
            // Default fetchByProperty returns null for properties other than TypeName/_id
            val result = IntIdPrincipal.fetchByProperty("name", "Test")
            assertNull(result, "Default fetchByProperty should return null for non-ID properties")
        }
    }

    @Test
    fun `default permitMasquerade returns false`() = runBlocking {
        IntIdPrincipal.store.clear()
        IntIdPrincipal.store[1] = IntIdPrincipal(1, "User1")
        IntIdPrincipal.store[2] = IntIdPrincipal(2, "User2")

        object : ServerBuilder() {
            init {
                register(IntIdPrincipal)
            }
        }.test({}) {
            val fromAuth = Authentication(
                principalType = IntIdPrincipal,
                id = 1,
                sessionId = null
            )
            val intoAuth = Authentication(
                principalType = IntIdPrincipal,
                id = 2,
                sessionId = null
            )

            assertFalse(IntIdPrincipal.permitMasquerade(fromAuth, intoAuth))
        }
    }

    @Test
    fun `default precache returns empty list`() {
        assertTrue(IntIdPrincipal.precache.isEmpty())
        assertTrue(StringIdPrincipal.precache.isEmpty())
    }

    @Test
    fun `getProperty returns property value for non-id properties`() = runBlocking {
        TestPrincipal.store.clear()
        val id = Uuid.random()
        val principal = TestPrincipal(id, "user@example.com", "555-1234", "Test User")
        TestPrincipal.store[id] = principal

        object : ServerBuilder() {
            init {
                register(TestPrincipal)
            }
        }.test({}) {
            val email = TestPrincipal.getProperty(principal, "email")
            assertNotNull(email)
            assertEquals("user@example.com", email)

            val displayName = TestPrincipal.getProperty(principal, "displayName")
            assertNotNull(displayName)
            assertEquals("Test User", displayName)
        }
    }

    @Test
    fun `getProperty returns null for nonexistent properties`() = runBlocking {
        TestPrincipal.store.clear()
        val id = Uuid.random()
        val principal = TestPrincipal(id, "user@example.com", "555-1234", "Test User")
        TestPrincipal.store[id] = principal

        object : ServerBuilder() {
            init {
                register(TestPrincipal)
            }
        }.test({}) {
            val nonexistent = TestPrincipal.getProperty(principal, "nonexistent")
            assertNull(nonexistent)
        }
    }

    @Test
    fun `fetchByProperty by id via TypeName slash _id format for Int ID`() = runBlocking {
        IntIdPrincipal.store.clear()
        val id = 42
        val principal = IntIdPrincipal(id, "Test Entity")
        IntIdPrincipal.store[id] = principal

        object : ServerBuilder() {
            init {
                register(IntIdPrincipal)
            }
        }.test({}) {
            val idString = IntIdPrincipal.idString(id)
            val fetched = IntIdPrincipal.fetchByProperty("IntIdPrincipal/_id", idString)
            assertNotNull(fetched)
            assertEquals(principal, fetched)
        }
    }

    // ========== Additional ID Type Tests ==========

    @Test
    fun `Int ID principal works correctly`() = runBlocking {
        IntIdPrincipal.store.clear()
        val id = 42
        val principal = IntIdPrincipal(id, "Test Entity")
        IntIdPrincipal.store[id] = principal

        object : ServerBuilder() {
            init {
                register(IntIdPrincipal)
            }
        }.test({}) {
            val fetched = IntIdPrincipal.fetch(id)
            assertEquals(principal, fetched)
            assertEquals("Test Entity", fetched.name)
        }
    }

    @Test
    fun `String ID principal works correctly`() = runBlocking {
        StringIdPrincipal.store.clear()
        val id = "custom-string-id-123"
        val principal = StringIdPrincipal(id, "Custom Label")
        StringIdPrincipal.store[id] = principal

        object : ServerBuilder() {
            init {
                register(StringIdPrincipal)
            }
        }.test({}) {
            val fetched = StringIdPrincipal.fetch(id)
            assertEquals(principal, fetched)
            assertEquals("Custom Label", fetched.label)
        }
    }

    @Test
    fun `fetchByProperty works with TypeName slash _id format`() = runBlocking {
        TestPrincipal.store.clear()
        val id = Uuid.random()
        val principal = TestPrincipal(id, "user@example.com", "555-1234", "User")
        TestPrincipal.store[id] = principal

        object : ServerBuilder() {
            init {
                register(TestPrincipal)
            }
        }.test({}) {
            // The default fetchByProperty supports "TypeName/_id" lookup
            val idString = TestPrincipal.idString(id)
            val fetched = TestPrincipal.fetchByProperty("TestPrincipal/_id", idString)
            assertNotNull(fetched)
            assertEquals(principal, fetched)
        }
    }

    // ========== fetchUserIdString Tests (by Claude) ==========

    @Test
    fun `fetchUserIdString returns serialized ID when subject found`() = runBlocking {
        TestPrincipal.store.clear()
        val id = Uuid.random()
        val principal = TestPrincipal(id, "user@example.com", "555-1234", "User")
        TestPrincipal.store[id] = principal

        object : ServerBuilder() {
            init {
                register(TestPrincipal)
            }
        }.test({}) {
            val result = TestPrincipal.fetchUserIdString("email", "user@example.com")
            assertNotNull(result)
            // The result should be the serialized ID, which matches idString output
            assertEquals(TestPrincipal.idString(id), result)
        }
    }

    @Test
    fun `fetchUserIdString returns null when subject not found`() = runBlocking {
        TestPrincipal.store.clear()
        val id = Uuid.random()
        val principal = TestPrincipal(id, "existing@example.com", "555-1234", "User")
        TestPrincipal.store[id] = principal

        object : ServerBuilder() {
            init {
                register(TestPrincipal)
            }
        }.test({}) {
            val result = TestPrincipal.fetchUserIdString("email", "nonexistent@example.com")
            assertNull(result)
        }
    }

    @Test
    fun `fetchUserIdString works with Int ID type`() = runBlocking {
        IntIdPrincipal.store.clear()
        val id = 42
        val principal = IntIdPrincipal(id, "Test Entity")
        IntIdPrincipal.store[id] = principal

        object : ServerBuilder() {
            init {
                register(IntIdPrincipal)
            }
        }.test({}) {
            // IntIdPrincipal only supports ID lookup via "IntIdPrincipal/_id"
            val idString = IntIdPrincipal.idString(id)
            val result = IntIdPrincipal.fetchUserIdString("IntIdPrincipal/_id", idString)
            assertNotNull(result)
            assertEquals(idString, result)
        }
    }

    @Test
    fun `fetchUserIdString returns null for unsupported properties`() = runBlocking {
        IntIdPrincipal.store.clear()
        val id = 42
        val principal = IntIdPrincipal(id, "Test Entity")
        IntIdPrincipal.store[id] = principal

        object : ServerBuilder() {
            init {
                register(IntIdPrincipal)
            }
        }.test({}) {
            // IntIdPrincipal uses default fetchByProperty which only supports "IntIdPrincipal/_id"
            val result = IntIdPrincipal.fetchUserIdString("name", "Test Entity")
            assertNull(result, "fetchUserIdString should return null for unsupported property lookups")
        }
    }

    @Test
    fun `fetchUserIdString uses normalized property value for lookup`() = runBlocking {
        TestPrincipal.store.clear()
        val id = Uuid.random()
        // Store with lowercase email
        val principal = TestPrincipal(id, "user@example.com", "5551234", "User")
        TestPrincipal.store[id] = principal

        object : ServerBuilder() {
            init {
                register(TestPrincipal)
            }
        }.test({}) {
            // TestPrincipal.fetchByProperty normalizes email to lowercase
            // So this should find the user even with uppercase input
            val result = TestPrincipal.fetchUserIdString("email", "USER@EXAMPLE.COM")
            assertNotNull(result, "Should find user even with different case email")
            assertEquals(TestPrincipal.idString(id), result)
        }
    }

    @Test
    fun `fetchUserIdString works with String ID type`() = runBlocking {
        StringIdPrincipal.store.clear()
        val id = "custom-string-id"
        val principal = StringIdPrincipal(id, "Label")
        StringIdPrincipal.store[id] = principal

        object : ServerBuilder() {
            init {
                register(StringIdPrincipal)
            }
        }.test({}) {
            val idString = StringIdPrincipal.idString(id)
            val result = StringIdPrincipal.fetchUserIdString("StringIdPrincipal/_id", idString)
            assertNotNull(result)
            assertEquals(idString, result)
        }
    }

    // ========== principalTypes Extension Tests (by Claude) ==========

    @Test
    fun `principalTypes contains registered types`() = runBlocking {
        object : ServerBuilder() {
            init {
                register(TestPrincipal)
                register(IntIdPrincipal)
            }
        }.test({}) {
            val types = serverRuntime.server.principalTypes
            assertNotNull(types)
            assertTrue(types.containsKey("TestPrincipal"))
            assertTrue(types.containsKey("IntIdPrincipal"))
            assertEquals(TestPrincipal, types["TestPrincipal"])
            assertEquals(IntIdPrincipal, types["IntIdPrincipal"])
        }
    }

    @Test
    fun `principalTypes is empty when no types registered`() = runBlocking {
        object : ServerBuilder() {}.test({}) {
            val types = serverRuntime.server.principalTypes
            assertNotNull(types)
            assertTrue(types.isEmpty())
        }
    }

    // ========== principalTypeFor Tests (by Claude) ==========

    @Test
    fun `principalTypeFor returns correct type`() = runBlocking {
        object : ServerBuilder() {
            init {
                register(TestPrincipal)
            }
        }.test({}) {
            val type = principalTypeFor<TestPrincipal, Uuid>()
            assertEquals(TestPrincipal, type)
        }
    }

    @Test
    fun `principalTypeFor returns correct type for Int ID`() = runBlocking {
        object : ServerBuilder() {
            init {
                register(IntIdPrincipal)
            }
        }.test({}) {
            val type = principalTypeFor<IntIdPrincipal, Int>()
            assertEquals(IntIdPrincipal, type)
        }
    }

    @Test
    fun `principalTypeFor throws for unregistered type`() = runBlocking {
        object : ServerBuilder() {
            init {
                register(TestPrincipal) // Only register TestPrincipal
            }
        }.test({}) {
            assertFailsWith<IllegalArgumentException> {
                principalTypeFor<IntIdPrincipal, Int>() // Not registered
            }
        }
    }

    @Test
    fun `principalTypeFor works with multiple registered types`() = runBlocking {
        object : ServerBuilder() {
            init {
                register(TestPrincipal)
                register(IntIdPrincipal)
                register(StringIdPrincipal)
            }
        }.test({}) {
            assertEquals(TestPrincipal, principalTypeFor<TestPrincipal, Uuid>())
            assertEquals(IntIdPrincipal, principalTypeFor<IntIdPrincipal, Int>())
            assertEquals(StringIdPrincipal, principalTypeFor<StringIdPrincipal, String>())
        }
    }
}
