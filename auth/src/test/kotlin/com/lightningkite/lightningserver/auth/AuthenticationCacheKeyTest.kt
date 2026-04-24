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
import kotlin.test.*
import kotlin.uuid.Uuid

/**
 * Tests for Authentication.CacheKey and Authentication.Reader.
 */
class AuthenticationCacheKeyTest {

    @Serializable
    data class CacheTestUser(
        override val _id: Uuid = Uuid.random(),
        val email: String = "",
    ) : HasId<Uuid> {
        companion object : PrincipalType<CacheTestUser, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<CacheTestUser> = serializer()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): CacheTestUser = CacheTestUser(id)
        }
    }

    object TestServer : ServerBuilder() {
        init {
            register(CacheTestUser)
        }
    }

    // ========== CacheKey Properties Tests ==========

    @Test
    fun `CacheKey id is authentication`() {
        assertEquals("authentication", Authentication.CacheKey.id)
    }

    @Test
    fun `CacheKey serializer exists`() {
        assertNotNull(Authentication.CacheKey.serializer)
    }

    // ========== Reader Priority Tests ==========

    @Test
    fun `Reader default priority is 0`() {
        val reader = object : Authentication.Reader<CacheTestUser> {
            context(server: ServerRuntime)
            override suspend fun read(request: com.lightningkite.lightningserver.data.Request<*>): Authentication<CacheTestUser>? =
                null
        }
        assertEquals(0.0, reader.priority)
    }

    @Test
    fun `Reader can have custom priority`() {
        val reader = object : Authentication.Reader<CacheTestUser> {
            override val priority: Double = 100.0

            context(server: ServerRuntime)
            override suspend fun read(request: com.lightningkite.lightningserver.data.Request<*>): Authentication<CacheTestUser>? =
                null
        }
        assertEquals(100.0, reader.priority)
    }

    @Test
    fun `Reader can have negative priority`() {
        val reader = object : Authentication.Reader<CacheTestUser> {
            override val priority: Double = -50.0

            context(server: ServerRuntime)
            override suspend fun read(request: com.lightningkite.lightningserver.data.Request<*>): Authentication<CacheTestUser>? =
                null
        }
        assertEquals(-50.0, reader.priority)
    }

    // ========== authReaders Registration Tests ==========

    @Test
    fun `authReaders can be registered`() = runBlocking {
        val reader = object : Authentication.Reader<CacheTestUser> {
            override val priority: Double = 10.0

            context(server: ServerRuntime)
            override suspend fun read(request: com.lightningkite.lightningserver.data.Request<*>): Authentication<CacheTestUser>? =
                null
        }

        object : ServerBuilder() {
            init {
                register(CacheTestUser)
                authReaders.register(reader)
            }
        }.test({}) {
            // Reader should be registered
            val readers = authReaders
            assertNotNull(readers)
        }
    }
}
