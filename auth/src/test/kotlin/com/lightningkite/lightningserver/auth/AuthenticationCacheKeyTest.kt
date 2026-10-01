// by Claude
package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.services.database.HasId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
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

    // ========== Masquerade Tests ==========

    @Serializable
    data class MasqueradeTarget(override val _id: Uuid = Uuid.random()) : HasId<Uuid> {
        companion object : PrincipalType<MasqueradeTarget, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<MasqueradeTarget> = serializer()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): MasqueradeTarget = MasqueradeTarget(id)

            context(server: ServerRuntime)
            override suspend fun permitMasquerade(
                from: Authentication<*>,
                into: Authentication<MasqueradeTarget>,
            ): Boolean = true
        }
    }

    @Test
    fun `masquerade keeps the original issuedAt so maxAge still applies`() = runBlocking {
        val adminId = Uuid.random()
        val targetId = Uuid.random()
        object : ServerBuilder() {
            init {
                register(CacheTestUser)
                register(MasqueradeTarget)
                authReaders.register(Authentication.Reader {
                    Authentication(CacheTestUser, adminId, sessionId = null, issuedAt = Clock.System.now() - 1.hours)
                })
            }
        }.test({}) {
            val masked = Authentication.CacheKey.calculate(
                HttpRequest<PathSpec>(
                    path = RawHttpEndpoint(asString = "/", method = HttpMethod.GET),
                    queryParameters = QueryParameters.EMPTY,
                    headers = HttpHeaders { add(HttpHeader.XMasquerade, "${MasqueradeTarget.name}/$targetId") },
                    domain = "example.com",
                    protocol = "https",
                    sourceIp = "local",
                    requestId = generateRequestId(),
                )
            )!!

            assertEquals(targetId.toString(), masked.rawId)
            assertIs<AuthRequirement.Result.Rejected>(AuthRequirement.Authenticated(maxAge = 10.minutes).check(masked))
        }
    }
}
