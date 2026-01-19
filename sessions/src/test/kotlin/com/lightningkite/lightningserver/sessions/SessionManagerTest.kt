// by Claude
package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.UnauthorizedException
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.GrantedScope
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.id
import com.lightningkite.lightningserver.auth.register
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.sessions.token.PrivateTinyTokenFormat
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.condition
import com.lightningkite.services.database.eq
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests for SessionManager - session-based authentication.
 */
class SessionManagerTest {

    @Serializable
    data class SessionTestUser(
        override val _id: Uuid = Uuid.random(),
        val email: String = ""
    ) : HasId<Uuid> {
        companion object : PrincipalType<SessionTestUser, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<SessionTestUser> = serializer()

            val users = mutableMapOf<Uuid, SessionTestUser>()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): SessionTestUser = users[id] ?: SessionTestUser(id)
        }
    }

    class TestSessionManager(
        database: Runtime<Database>,
        private val expirationDuration: Duration? = 30.days,
        private val staleDuration: Duration? = 7.days
    ) : SessionManager<SessionTestUser, Uuid>(
        principal = SessionTestUser,
        database = database,
        tokenFormat = Runtime { PrivateTinyTokenFormat() }
    ) {
        context(server: ServerRuntime)
        override suspend fun sessionExpiration(subject: SessionTestUser): Instant? =
            expirationDuration?.let { com.lightningkite.lightningserver.runtime.now() + it }

        context(server: ServerRuntime)
        override suspend fun sessionStaleAfter(subject: SessionTestUser): Duration? = staleDuration
    }

    @Test
    fun `newSession creates a session and returns refresh token`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com")
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                // Create a new session
                val (session, refreshToken) = server.sessions.newSession(userId)

                // Verify session properties
                assertEquals(userId, session.subjectId)
                assertNotNull(session._id)
                assertNotNull(session.secretHash)
                assertNotNull(session.createdAt)
                assertNotNull(session.lastUsed)
                assertEquals(setOf(GrantedScope.root), session.scopes)

                // Verify refresh token properties
                assertNotNull(refreshToken.string)
                assertEquals("SessionTestUser", refreshToken.type)
                assertEquals(session._id, refreshToken._id)
            }
        }
    }

    @Test
    fun `tokenSimple exchanges refresh token for access token`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com")
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                // Create a new session
                val (session, refreshToken) = server.sessions.newSession(userId)

                // Exchange refresh token for access token
                val accessToken = server.sessions.tokenSimple.test(null, refreshToken.string)

                assertNotNull(accessToken)
                assertTrue(accessToken.isNotEmpty())
            }
        }
    }

    @Test
    fun `tokenSimple rejects invalid refresh token`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com")
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                // Try with invalid refresh token
                assertFailsWith<Exception>("Invalid refresh token should be rejected") {
                    server.sessions.tokenSimple.test(null, "invalid_token_string")
                }
            }
        }
    }

    @Test
    fun `session toAuth converts session to authentication`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com")
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                // Create a new session
                val (session, _) = server.sessions.newSession(userId)

                // Convert to authentication
                val auth = with(server.sessions) { session.toAuth() }

                assertEquals("SessionTestUser", auth.principalName)
                assertEquals(userId, auth.id)
                assertEquals(session._id.toString(), auth.sessionId)
                assertEquals(session.scopes, auth.scopes)
            }
        }
    }

    @Test
    fun `newSession with custom scopes limits permissions`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com")
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val limitedScopes = setOf(GrantedScope("api:read"))

                // Create a session with limited scopes
                val (session, _) = server.sessions.newSession(
                    subjectId = userId,
                    scopes = limitedScopes
                )

                assertEquals(limitedScopes, session.scopes)
            }
        }
    }

    @Test
    fun `newSession with label stores the label`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com")
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (session, _) = server.sessions.newSession(
                    subjectId = userId,
                    label = "Mobile App"
                )

                assertEquals("Mobile App", session.label)
            }
        }
    }

    @Test
    fun `newSession with derivedFrom links to parent session`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com")
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                // Create parent session
                val (parentSession, _) = server.sessions.newSession(userId)

                // Create derived session
                val (childSession, _) = server.sessions.newSession(
                    subjectId = userId,
                    derivedFrom = parentSession._id
                )

                assertEquals(parentSession._id, childSession.derivedFrom)
            }
        }
    }

    @Test
    fun `multiple sessions can exist for same user`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com")
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                // Create multiple sessions
                val (session1, token1) = server.sessions.newSession(userId, label = "Session 1")
                val (session2, token2) = server.sessions.newSession(userId, label = "Session 2")
                val (session3, token3) = server.sessions.newSession(userId, label = "Session 3")

                // All should have different IDs
                assertNotEquals(session1._id, session2._id)
                assertNotEquals(session2._id, session3._id)
                assertNotEquals(session1._id, session3._id)

                // All tokens should work
                assertNotNull(server.sessions.tokenSimple.test(null, token1.string))
                assertNotNull(server.sessions.tokenSimple.test(null, token2.string))
                assertNotNull(server.sessions.tokenSimple.test(null, token3.string))
            }
        }
    }

    @Test
    fun `session secret is hashed not stored plaintext`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com")
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (session, refreshToken) = server.sessions.newSession(userId)

                // The secret in the refresh token should NOT equal the hash in the session
                assertNotEquals(refreshToken.plainTextSecret, session.secretHash)

                // The hash should be significantly different (hashing changes the value)
                assertTrue(session.secretHash.length > 10)
            }
        }
    }

    @Test
    fun `refresh token structure is valid`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com")
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (session, refreshToken) = server.sessions.newSession(userId)

                // Check refresh token can be reconstructed
                val reconstructed = RefreshToken(refreshToken.string)
                assertTrue(reconstructed.valid)
                assertEquals(refreshToken.type, reconstructed.type)
                assertEquals(refreshToken._id, reconstructed._id)
                assertEquals(refreshToken.plainTextSecret, reconstructed.plainTextSecret)
            }
        }
    }
}
