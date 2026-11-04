// by Claude
package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.encryption.HS256
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.sessions.token.JwtTokenFormat
import com.lightningkite.lightningserver.sessions.token.PrivateTinyTokenFormat
import com.lightningkite.lightningserver.sessions.token.TokenFormat
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.get
import com.lightningkite.services.database.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests for revoking access tokens early with `tokenInvalidationChecks`: [SessionManager.invalidateSession],
 * [SessionManager.invalidateSubject], and the invalidation done when a session is terminated or deleted.
 *
 * Every test runs on a controlled clock and advances it between minting a token and invalidating, because an
 * invalidation only rejects tokens minted strictly before it.
 */
class TokenInvalidationTest {

    @Serializable
    data class InvalidationUser(override val _id: Uuid = Uuid.random()) : HasId<Uuid> {
        companion object : PrincipalType<InvalidationUser, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<InvalidationUser> = serializer()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): InvalidationUser = InvalidationUser(id)
        }
    }

    class TestSessionManager(
        database: Runtime<Database>,
        cache: Runtime<Cache>?,
        tokenFormat: Runtime<TokenFormat> = Runtime { PrivateTinyTokenFormat() },
        tokenInvalidationChecks: Boolean = true,
    ) : SessionManager<InvalidationUser, Uuid>(
        principal = InvalidationUser,
        database = database,
        tokenFormat = tokenFormat,
        cache = cache,
        tokenInvalidationChecks = tokenInvalidationChecks,
    ) {
        context(server: ServerRuntime)
        override suspend fun sessionExpiration(subject: InvalidationUser): Instant? =
            com.lightningkite.lightningserver.runtime.now() + 30.days

        context(server: ServerRuntime)
        override suspend fun sessionStaleAfter(subject: InvalidationUser): Duration? = 7.days
    }

    class TestAuthEndpoints(
        database: Runtime<Database>,
        cache: Runtime<Cache>,
    ) : AuthEndpoints<InvalidationUser, Uuid>(
        principal = InvalidationUser,
        database = database,
        cache = cache,
        tokenInvalidationChecks = true,
    ) {
        context(server: ServerRuntime)
        override suspend fun sessionExpiration(subject: InvalidationUser): Instant? =
            com.lightningkite.lightningserver.runtime.now() + 30.days

        context(server: ServerRuntime)
        override suspend fun sessionStaleAfter(subject: InvalidationUser): Duration? = 7.days

        context(server: ServerRuntime)
        override suspend fun requiredProofStrengthFor(subject: InvalidationUser): Int = 100
    }

    /** A cache whose writes always fail, standing in for a cache outage. Reads still work. */
    class WriteFailingCache(real: Cache) : Cache by real {
        override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?) {
            throw IllegalStateException("Cache is down")
        }
    }

    private class TestServer(
        tokenFormat: Runtime<TokenFormat> = Runtime { PrivateTinyTokenFormat() },
        tokenInvalidationChecks: Boolean = true,
        failCacheWrites: Boolean = false,
    ) : ServerBuilder() {
        val database = setting("database", Database.Settings("ram"))
        val cache = setting("cache", Cache.Settings("ram"))
        val sessionCache: Runtime<Cache> =
            if (failCacheWrites) Runtime { WriteFailingCache(cache()) } else cache

        val sessions = path.path("auth") include TestSessionManager(
            database = database,
            cache = sessionCache,
            tokenFormat = tokenFormat,
            tokenInvalidationChecks = tokenInvalidationChecks,
        )
    }

    private var time: Instant = Instant.parse("2030-01-01T00:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = time
    }

    context(server: ServerRuntime)
    private suspend fun SessionManager<InvalidationUser, Uuid>.readBearer(token: String): Authentication<InvalidationUser>? =
        read(
            HttpRequest<PathSpec>(
                path = RawHttpEndpoint(asString = "/", method = HttpMethod.GET),
                queryParameters = QueryParameters.EMPTY,
                headers = HttpHeaders { add(HttpHeader.Authorization, "Bearer $token") },
                domain = "example.com",
                protocol = "https",
                sourceIp = "local",
                requestId = generateRequestId(),
            )
        )

    private fun jwtFormat(): Runtime<TokenFormat> {
        val basis = SecretBasis()
        return Runtime {
            JwtTokenFormat(
                hasher = RuntimeDeferred.Cached { basis.HS256("jwt") },
                issuer = Runtime { "https://test.example.com" },
                audience = Runtime { "https://test.example.com" },
                keyId = "InvalidationUser",
            )
        }
    }

    @Test
    fun `invalidateSession rejects earlier access tokens and keeps the session usable`() = runBlocking {
        val server = TestServer()
        server.test({}, clock = { clock }) {
            val (_, refresh) = server.sessions.newSession(Uuid.random())
            val before = server.sessions.tokenSimple.test(null, refresh.string)
            assertNotNull(server.sessions.readBearer(before))

            time += 1.seconds
            server.sessions.invalidateSession(RefreshToken(refresh.string)._id)

            assertFailsWith<UnauthorizedException> { server.sessions.readBearer(before) }

            // The session itself is untouched: its refresh token still works, both for a new access token and as a
            // bearer token directly.
            time += 1.seconds
            val after = server.sessions.tokenSimple.test(null, refresh.string)
            assertNotNull(server.sessions.readBearer(after))
            assertNotNull(server.sessions.readBearer(refresh.string))
        }
    }

    @Test
    fun `invalidateSession only affects that session`() = runBlocking {
        val server = TestServer()
        server.test({}, clock = { clock }) {
            val userId = Uuid.random()
            val (invalidated, invalidatedRefresh) = server.sessions.newSession(userId)
            val (_, otherRefresh) = server.sessions.newSession(userId)
            val invalidatedToken = server.sessions.tokenSimple.test(null, invalidatedRefresh.string)
            val otherToken = server.sessions.tokenSimple.test(null, otherRefresh.string)

            time += 1.seconds
            server.sessions.invalidateSession(invalidated._id)

            assertFailsWith<UnauthorizedException> { server.sessions.readBearer(invalidatedToken) }
            assertNotNull(server.sessions.readBearer(otherToken))
        }
    }

    @Test
    fun `invalidateSubject rejects earlier access tokens from every session of that subject only`() = runBlocking {
        val server = TestServer()
        server.test({}, clock = { clock }) {
            val userId = Uuid.random()
            val (_, firstRefresh) = server.sessions.newSession(userId)
            val (_, secondRefresh) = server.sessions.newSession(userId)
            val (_, otherUserRefresh) = server.sessions.newSession(Uuid.random())
            val first = server.sessions.tokenSimple.test(null, firstRefresh.string)
            val second = server.sessions.tokenSimple.test(null, secondRefresh.string)
            val otherUser = server.sessions.tokenSimple.test(null, otherUserRefresh.string)

            time += 1.seconds
            server.sessions.invalidateSubject(userId)

            assertFailsWith<UnauthorizedException> { server.sessions.readBearer(first) }
            assertFailsWith<UnauthorizedException> { server.sessions.readBearer(second) }
            assertNotNull(server.sessions.readBearer(otherUser))

            time += 1.seconds
            assertNotNull(server.sessions.readBearer(server.sessions.tokenSimple.test(null, firstRefresh.string)))
        }
    }

    @Test
    fun `an access token minted at the same instant as an invalidation is accepted`() = runBlocking {
        // The check is "invalidated after the token was minted", so a token from the same instant survives.
        val server = TestServer()
        server.test({}, clock = { clock }) {
            val (session, refresh) = server.sessions.newSession(Uuid.random())
            server.sessions.invalidateSession(session._id)
            assertNotNull(server.sessions.readBearer(server.sessions.tokenSimple.test(null, refresh.string)))
        }
    }

    @Test
    fun `terminating a session rejects its access tokens and those of sessions derived from it`() = runBlocking {
        val server = TestServer()
        server.test({}, clock = { clock }) {
            val (parent, parentRefresh) = server.sessions.newSession(Uuid.random())
            val childRefresh = server.sessions.createSubSession.test(
                with(server.sessions) { parent.toAuth() },
                SubSessionRequest(label = "child")
            )
            val parentToken = server.sessions.tokenSimple.test(null, parentRefresh.string)
            val childToken = server.sessions.tokenSimple.test(null, childRefresh)

            time += 1.seconds
            server.sessions.sessionTerminate.test(with(server.sessions) { parent.toAuth() }, Unit)

            assertFailsWith<UnauthorizedException> { server.sessions.readBearer(parentToken) }
            assertFailsWith<UnauthorizedException> { server.sessions.readBearer(childToken) }
        }
    }

    @Test
    fun `deleting a session rejects its access tokens`() = runBlocking {
        val server = TestServer()
        server.test({}, clock = { clock }) {
            val (session, refresh) = server.sessions.newSession(Uuid.random())
            val token = server.sessions.tokenSimple.test(null, refresh.string)

            time += 1.seconds
            server.sessions.sessionInfo.table().deleteOneById(session._id)

            assertFailsWith<UnauthorizedException> { server.sessions.readBearer(token) }
        }
    }

    @Test
    fun `terminating a session not used within a token lifetime records no invalidation`() = runBlocking {
        // Its access tokens have all expired, so there is nothing to reject. This checks the cache entry directly
        // because the effect on tokens can't be observed: they are expired either way.
        val server = TestServer()
        server.test({}, clock = { clock }) {
            val userId = Uuid.random()
            val (idle, _) = server.sessions.newSession(userId)
            val (recent, recentRefresh) = server.sessions.newSession(userId)

            time += PrivateTinyTokenFormat().expiration + 1.minutes
            server.sessions.tokenSimple.test(null, recentRefresh.string)  // refreshes recent's lastUsed
            time += 1.seconds

            for (session in listOf(idle, recent)) {
                server.sessions.sessionTerminate.test(with(server.sessions) { session.toAuth() }, Unit)
            }

            assertNull(server.cache().get<Instant>("invalid-session:${idle._id}"))
            assertNotNull(server.cache().get<Instant>("invalid-session:${recent._id}"))
        }
    }

    @Test
    fun `a cache failure while invalidating does not stop termination or its cascade`() = runBlocking {
        val server = TestServer(failCacheWrites = true)
        server.test({}, clock = { clock }) {
            val (parent, parentRefresh) = server.sessions.newSession(Uuid.random())
            val childRefresh = server.sessions.createSubSession.test(
                with(server.sessions) { parent.toAuth() },
                SubSessionRequest(label = "child")
            )
            server.sessions.tokenSimple.test(null, parentRefresh.string)
            server.sessions.tokenSimple.test(null, childRefresh)

            time += 1.seconds
            server.sessions.sessionTerminate.test(with(server.sessions) { parent.toAuth() }, Unit)

            assertNotNull(server.sessions.sessionInfo.table().get(parent._id)?.terminated)
            assertFailsWith<UnauthorizedException> { server.sessions.tokenSimple.test(null, childRefresh) }
        }
    }

    @Test
    fun `with checks off, invalidation does nothing and access tokens stay valid`() = runBlocking {
        val server = TestServer(tokenInvalidationChecks = false)
        server.test({}, clock = { clock }) {
            val userId = Uuid.random()
            val (session, refresh) = server.sessions.newSession(userId)
            val token = server.sessions.tokenSimple.test(null, refresh.string)

            time += 1.seconds
            server.sessions.invalidateSession(session._id)
            server.sessions.invalidateSubject(userId)
            assertNotNull(server.sessions.readBearer(token))

            // Termination still ends the session, but its access tokens live until they expire.
            server.sessions.sessionTerminate.test(with(server.sessions) { session.toAuth() }, Unit)
            assertNotNull(server.sessions.readBearer(token))
            assertNull(server.cache().get<Instant>("invalid-session:${session._id}"))
        }
    }

    @Test
    fun `checks on without a cache are rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            TestSessionManager(database = Runtime { error("unused") }, cache = null, tokenInvalidationChecks = true)
        }
    }

    @Test
    fun `checks off without a cache is allowed`() = runBlocking {
        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val sessions = path.path("auth") include TestSessionManager(
                database = database,
                cache = null,
                tokenInvalidationChecks = false,
            )
        }.let { server ->
            server.test({}) {
                val (_, refresh) = server.sessions.newSession(Uuid.random())
                val token = server.sessions.tokenSimple.test(null, refresh.string)
                assertNotNull(server.sessions.readBearer(token))
            }
        }
    }

    @Test
    fun `AuthEndpoints passes its cache through for token invalidation`() = runBlocking {
        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))
            val auth = path.path("auth") include TestAuthEndpoints(database = database, cache = cache)
        }.let { server ->
            server.test({}, clock = { clock }) {
                val (session, refresh) = server.auth.newSession(Uuid.random())
                val token = server.auth.tokenSimple.test(null, refresh.string)

                time += 1.seconds
                server.auth.invalidateSession(session._id)

                assertFailsWith<UnauthorizedException> { server.auth.readBearer(token) }
            }
        }
    }

    @Test
    fun `JWT access tokens are rejected after invalidation`() = runBlocking {
        val server = TestServer(tokenFormat = jwtFormat())
        server.test({}, clock = { clock }) {
            val (session, refresh) = server.sessions.newSession(Uuid.random())
            val before = server.sessions.tokenSimple.test(null, refresh.string)
            assertNotNull(server.sessions.readBearer(before))

            time += 1.seconds
            server.sessions.invalidateSession(session._id)

            assertFailsWith<UnauthorizedException> { server.sessions.readBearer(before) }
            time += 1.seconds
            assertNotNull(server.sessions.readBearer(server.sessions.tokenSimple.test(null, refresh.string)))
        }
    }

    @Test
    fun `a JWT minted later in the same second as an invalidation is rejected`() = runBlocking {
        // Deliberate fail-closed behavior: JWT iat is whole seconds, so it can't show the token came after the
        // invalidation. The client has to refresh again, after the second ends.
        time = Instant.parse("2030-01-01T00:00:00.300Z")
        val server = TestServer(tokenFormat = jwtFormat())
        server.test({}, clock = { clock }) {
            val (session, refresh) = server.sessions.newSession(Uuid.random())
            server.sessions.invalidateSession(session._id)

            time = Instant.parse("2030-01-01T00:00:00.700Z")
            val sameSecond = server.sessions.tokenSimple.test(null, refresh.string)
            assertFailsWith<UnauthorizedException> { server.sessions.readBearer(sameSecond) }

            time = Instant.parse("2030-01-01T00:00:01Z")
            assertNotNull(server.sessions.readBearer(server.sessions.tokenSimple.test(null, refresh.string)))
        }
    }
}
