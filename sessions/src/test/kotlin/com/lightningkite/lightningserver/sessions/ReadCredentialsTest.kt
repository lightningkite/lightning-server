package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.pathing.RawWebSocketPath
import com.lightningkite.lightningserver.runtime.Execution
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.sessions.SessionManagerTest.SessionTestUser
import com.lightningkite.lightningserver.sessions.SessionManagerTest.TestSessionManager
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.*
import com.lightningkite.lightningserver.auth.id
import com.lightningkite.lightningserver.typed.test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class ReadCredentialsTest {
    private class TestServer(acceptRefreshTokenAsBearer: Boolean) : ServerBuilder() {
        init { registerBasicMediaTypeCoders() }

        val database = setting("database", Database.Settings("ram"))
        val cache = setting("cache", Cache.Settings("ram"))

        val sessions = path.path("auth") include TestSessionManager(
            database = database,
            cache = cache,
            acceptRefreshTokenAsBearer = Runtime { acceptRefreshTokenAsBearer },
        )
    }

    private fun newUser(): Uuid = Uuid.random().also { SessionTestUser.users[it] = SessionTestUser(it) }

    @Test
    fun `an access token in the Authorization header authenticates`() = runBlocking {
        val server = TestServer(acceptRefreshTokenAsBearer = false)
        server.test({}) {
            val (_, refresh) = execute { server.sessions.newSession(newUser()) }
            val access = server.sessions.tokenSimple.test(null, refresh.string)

            val response = server.sessions.self.test(headers = HttpHeaders(HttpHeader.Authorization to "Bearer $access"))
            assertEquals(HttpStatus.OK, response.status)
        }
    }

    @Test
    fun `a refresh token is not a bearer credential by default`() = runBlocking {
        val server = TestServer(acceptRefreshTokenAsBearer = false)
        server.test({}) {
            val (session, refresh) = execute { server.sessions.newSession(newUser()) }

            val response = server.sessions.self.test(headers = HttpHeaders(HttpHeader.Authorization to "Bearer ${refresh.string}"))
            assertEquals(HttpStatus.Unauthorized, response.status)
            // Rejected before the session was looked at, so the use was not recorded.
            assertEquals(session.lastUsed, execute { server.sessions.sessionInfo.table().get(session._id) }!!.lastUsed)
        }
    }

    @Test
    fun `refused credentials get RFC 6750 bearer challenges`() = runBlocking {
        val server = TestServer(acceptRefreshTokenAsBearer = false)
        server.test({}) {
            suspend fun challenge(authorization: String?) = server.sessions.self.test(
                headers = authorization?.let { HttpHeaders(HttpHeader.Authorization to it) } ?: HttpHeaders.EMPTY
            ).let { it.status to it.headers[HttpHeader.WWWAuthenticate]?.root }

            val (_, refresh) = execute { server.sessions.newSession(newUser()) }
            assertEquals(HttpStatus.Unauthorized to "Bearer", challenge(null))
            // No reader recognizes it.
            assertEquals(HttpStatus.Unauthorized to "Bearer error=\"invalid_token\"", challenge("Bearer not-a-token"))
            // The session manager reads it and throws.
            assertEquals(HttpStatus.Unauthorized to "Bearer error=\"invalid_token\"", challenge("Bearer ${refresh.string}"))
        }
    }

    @Test
    fun `acceptRefreshTokenAsBearer lets a refresh token authenticate, except a rotating session's, which it leaves alone`() = runBlocking {
        val server = TestServer(acceptRefreshTokenAsBearer = true)
        server.test({}) {
            val (_, legacy) = execute { server.sessions.newSession(newUser()) }
            assertEquals(
                HttpStatus.OK,
                server.sessions.self.test(headers = HttpHeaders(HttpHeader.Authorization to "Bearer ${legacy.string}")).status
            )

            val (rotating, refresh) = execute { server.sessions.newSession(newUser()) }
            val cookie = server.sessions.setRefreshTokenCookie.test(
                body = TypedData.text(Json.encodeToString(refresh.string), MediaType.Application.Json)
            ).headers.getMany(HttpHeader.SetCookie).single().toHttpString()
                .substringAfter("RefreshToken=").substringBefore(';')

            assertEquals(
                HttpStatus.Unauthorized,
                server.sessions.self.test(headers = HttpHeaders(HttpHeader.Authorization to "Bearer $cookie")).status
            )
            assertNull(execute { server.sessions.sessionInfo.table().get(rotating._id) }!!.terminated)
        }
    }

    @Test
    fun `a token in the URL of an HTTP request is a bad request`() = runBlocking {
        val server = TestServer(acceptRefreshTokenAsBearer = false)
        server.test({}) {
            val (_, refresh) = execute { server.sessions.newSession(newUser()) }
            val access = server.sessions.tokenSimple.test(null, refresh.string)

            for (name in listOf("jwt", "Authorization", "authorization")) {
                val response = server.sessions.self.test(queryParameters = QueryParameters(listOf(name to access)))
                assertEquals(HttpStatus.BadRequest, response.status, name)
            }
        }
    }

    @Test
    fun `the Authorization cookie is not read`() = runBlocking {
        val server = TestServer(acceptRefreshTokenAsBearer = false)
        server.test({}) {
            val (_, refresh) = execute { server.sessions.newSession(newUser()) }
            val access = server.sessions.tokenSimple.test(null, refresh.string)

            val response = server.sessions.self.test(headers = HttpHeaders(HttpHeader.Cookie to "Authorization=$access"))
            // Treated as no credentials at all, which a required-auth endpoint answers with a 401 challenge.
            assertEquals(HttpStatus.Unauthorized, response.status)
            assertEquals("Bearer", response.headers[HttpHeader.WWWAuthenticate]?.root)
            assertEquals(server.sessions.self.test().status, response.status)
        }
    }

    @Test
    fun `a WebSocket connect still accepts an access token in the query, but not a refresh token`() = runBlocking {
        val server = TestServer(acceptRefreshTokenAsBearer = false)
        server.test({}) {
            val userId = newUser()
            val (_, refresh) = execute { server.sessions.newSession(userId) }
            val access = server.sessions.tokenSimple.test(null, refresh.string)

            fun connect(vararg query: Pair<String, String>) = WebSocketConnectRequest(
                path = RawWebSocketPath("/socket"),
                queryParameters = QueryParameters(query.toList()),
                socketId = Execution.ID.generate(),
            )

            assertEquals(userId, execute { server.sessions.read(connect("jwt" to access)) }?.id)
            assertEquals(userId, execute { server.sessions.read(connect("Authorization" to access)) }?.id)
            assertNull(execute { server.sessions.read(connect()) })
            kotlin.test.assertFailsWith<com.lightningkite.lightningserver.UnauthorizedException> {
                execute { server.sessions.read(connect("jwt" to refresh.string)) }
            }
        }
    }
}
