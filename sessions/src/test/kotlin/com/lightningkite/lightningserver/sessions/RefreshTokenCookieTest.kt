package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.auth.id
import com.lightningkite.lightningserver.definition.GeneralServerSettings
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.encryption.fastHash
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.sessions.SessionManagerTest.SessionTestUser
import com.lightningkite.lightningserver.sessions.SessionManagerTest.TestSessionManager
import com.lightningkite.lightningserver.sessions.proofs.IdentificationAndPassword
import com.lightningkite.lightningserver.sessions.proofs.PasswordProofEndpoints
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.lightningserver.typed.BulkRequest
import com.lightningkite.lightningserver.typed.MetaEndpoints
import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.TestRunner
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RefreshTokenCookieTest {
    private val server = object : ServerBuilder() {
        init { registerBasicMediaTypeCoders() }

        val database = setting("database", Database.Settings("ram"))
        val cache = setting("cache", Cache.Settings("ram"))

        val sessions = path.path("auth") include TestSessionManager(database = database, cache = cache)
        val meta = path.path("meta") include MetaEndpoints("test", cache)

        val events = install(object : AuthEventListener {
            val received = mutableListOf<AuthEvent>()

            context(runtime: ServerRuntime)
            override suspend fun onEvent(event: AuthEvent) {
                received += event
            }
        })
    }

    private var time = Clock.System.now()
    private val clock = object : Clock {
        override fun now() = time
    }

    private fun jsonBody(value: String) = TypedData.text(Json.encodeToString(value), MediaType.Application.Json)
    private suspend fun HttpResponse.stringBody(): String = Json.decodeFromString(body!!.text())
    private fun HttpResponse.setCookies() = headers.getMany(HttpHeader.SetCookie).map { it.toHttpString() }

    private fun cookieValue(setCookie: String): String = setCookie.substringAfter("RefreshToken=").substringBefore(';')

    private fun newUser(): Uuid = Uuid.random().also { SessionTestUser.users[it] = SessionTestUser(it) }

    @Test
    fun `setting the cookie scopes an HttpOnly refresh token to the cookie token endpoint`() = runBlocking {
        server.test({}) {
            val expires = Instant.parse("2031-01-02T03:04:05Z")
            val (session, refresh) = execute { server.sessions.newSession(newUser(), expires = expires) }

            val response = server.sessions.setRefreshTokenCookie.test(body = jsonBody(refresh.string))

            assertEquals(HttpStatus.OK, response.status)
            assertNull(response.body)
            val cookie = cookieValue(response.setCookies().single())
            assertTrue(cookie.startsWith("refresh/SessionTestUser/${session._id}:"), cookie)
            assertNotEquals(refresh.string, cookie)
            assertEquals(
                listOf("RefreshToken=$cookie; Expires=Thu, 2 Jan 2031 03:04:05 GMT; Path=/auth/token/cookie; Secure; HttpOnly; SameSite=Strict"),
                response.setCookies()
            )
        }
    }

    @Test
    fun `the cookie path includes the public URL's path`() = runBlocking {
        server.test({ generalSettings set GeneralServerSettings(publicUrl = "https://example.com/api/") }) {
            val (_, refresh) = execute { server.sessions.newSession(newUser()) }
            val cookie = server.sessions.setRefreshTokenCookie.test(body = jsonBody(refresh.string)).setCookies().single()
            assertContains(cookie, "; Path=/api/auth/token/cookie;")
        }
    }

    @Test
    fun `setting the cookie rejects a bad refresh token and sets nothing`() = runBlocking {
        server.test({}) {
            val (session, _) = execute { server.sessions.newSession(newUser()) }

            val wrongSecret = server.sessions.setRefreshTokenCookie.test(
                body = jsonBody(RefreshToken(SessionTestUser.name, session._id, "wrong").string)
            )
            assertEquals(HttpStatus.Unauthorized, wrongSecret.status)
            assertEquals(listOf(), wrongSecret.setCookies())

            val malformed = server.sessions.setRefreshTokenCookie.test(body = jsonBody("not a token"))
            assertEquals(HttpStatus.BadRequest, malformed.status)
            assertEquals(listOf(), malformed.setCookies())
        }
    }

    @Test
    fun `setting the cookie accepts only JSON, so a cross-site form cannot plant a session`() = runBlocking {
        server.test({}) {
            val (session, refresh) = execute { server.sessions.newSession(newUser()) }

            val bodies = listOf(
                // The form decoder reads a lone String from the field `value`, so without the check this would work.
                TypedData.text(
                    "value=${java.net.URLEncoder.encode(refresh.string, Charsets.UTF_8)}",
                    MediaType.Application.FormUrlEncoded
                ),
                TypedData.text(refresh.string, MediaType.Text.Plain),
                TypedData.text(Json.encodeToString(refresh.string), MediaType.Text.Plain),
            )
            for (body in bodies) {
                val response = server.sessions.setRefreshTokenCookie.test(body = body)
                assertEquals(HttpStatus.BadRequest, response.status, "for ${body.mediaType}")
                assertEquals(listOf(), response.setCookies())
            }
            // The rejected attempts never reached the session, so it was not marked used.
            assertEquals(session.lastUsed, execute { server.sessions.sessionInfo.table().get(session._id) }!!.lastUsed)

            val withCharset = TypedData.text(
                Json.encodeToString(refresh.string),
                MediaType.Application.Json.copy(parameters = mapOf("charset" to "utf-8"))
            )
            assertEquals(1, server.sessions.setRefreshTokenCookie.test(body = withCharset).setCookies().size)
        }
    }

    @Test
    fun `the cookie token endpoint returns an access token for the cookie and 401s without it`() = runBlocking {
        server.test({}) {
            val userId = newUser()
            val (_, refresh) = execute { server.sessions.newSession(userId) }

            val response = server.sessions.tokenFromCookie.test(
                headers = HttpHeaders(HttpHeader.Cookie to "other=1; RefreshToken=${refresh.string}")
            )
            assertEquals(HttpStatus.OK, response.status)
            val auth = execute { server.sessions.tokenFormat().read(SessionTestUser, response.stringBody()) }
            assertEquals(userId, auth?.id)

            assertEquals(HttpStatus.Unauthorized, server.sessions.tokenFromCookie.test().status)
            assertEquals(
                HttpStatus.Unauthorized,
                server.sessions.tokenFromCookie.test(headers = HttpHeaders(HttpHeader.Cookie to "other=1")).status
            )
        }
    }

    @Test
    fun `terminating the session deletes the cookie, after which the cookie token endpoint 401s`() = runBlocking {
        server.test({}) {
            val (session, refresh) = execute { server.sessions.newSession(newUser()) }
            val accessToken = server.sessions.tokenSimple.test(null, refresh.string)

            val response = server.sessions.sessionTerminate.test(
                headers = HttpHeaders(HttpHeader.Authorization to "Bearer $accessToken")
            )

            assertEquals(HttpStatus.OK, response.status)
            assertEquals(
                listOf("RefreshToken=deleted; Max-Age=0; Path=/auth/token/cookie; Secure; HttpOnly; SameSite=Strict"),
                response.setCookies()
            )
            assertNotNull(execute { server.sessions.sessionInfo.table().get(session._id) }?.terminated)
            assertEquals(
                HttpStatus.Unauthorized,
                server.sessions.tokenFromCookie.test(
                    headers = HttpHeaders(HttpHeader.Cookie to "RefreshToken=${refresh.string}")
                ).status
            )
        }
    }

    @Test
    fun `with refreshTokenCookieSecure off, setting and deleting the cookie omit Secure`() = runBlocking {
        val httpServer = object : ServerBuilder() {
            init { registerBasicMediaTypeCoders() }

            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(
                database = database,
                cache = cache,
                refreshTokenCookieSecure = Runtime { false },
            )
        }
        httpServer.test({}) {
            val (_, refresh) = execute {
                httpServer.sessions.newSession(newUser(), expires = Instant.parse("2031-01-02T03:04:05Z"))
            }
            val accessToken = httpServer.sessions.tokenSimple.test(null, refresh.string)

            val setCookies = httpServer.sessions.setRefreshTokenCookie.test(body = jsonBody(refresh.string)).setCookies()
            assertEquals(
                listOf("RefreshToken=${cookieValue(setCookies.single())}; Expires=Thu, 2 Jan 2031 03:04:05 GMT; Path=/auth/token/cookie; HttpOnly; SameSite=Strict"),
                setCookies
            )

            assertEquals(
                listOf("RefreshToken=deleted; Max-Age=0; Path=/auth/token/cookie; HttpOnly; SameSite=Strict"),
                httpServer.sessions.sessionTerminate.test(
                    headers = HttpHeaders(HttpHeader.Authorization to "Bearer $accessToken")
                ).setCookies()
            )
        }
    }

    @Test
    fun `tokenSimple responds as before, with no cookie`() = runBlocking {
        server.test({}) {
            val (_, refresh) = execute { server.sessions.newSession(newUser()) }

            val response = server.sessions.tokenSimple.test(body = jsonBody(refresh.string))

            assertEquals(HttpStatus.OK, response.status)
            assertEquals(HttpHeaders { add(HttpHeader.Vary, HttpHeader.Accept) }, response.headers)
            assertNotNull(execute { server.sessions.tokenFormat().read(SessionTestUser, response.stringBody()) })
            val stored = execute { server.sessions.sessionInfo.table().get(RefreshToken(refresh.string)._id) }!!
            assertNull(stored.previousSecretHash)
            assertNull(stored.rotatedAt)
        }
    }

    @Test
    fun `login2 responds as before, with the refresh token in the body and no cookie`() = runBlocking {
        val basis = SecretBasis()
        val authServer = object : ServerBuilder() {
            init { registerBasicMediaTypeCoders() }

            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            val passwords = path.path("proof").path("password") include PasswordProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { basis.signer("proof") },
                proofExpiration = 1.hours,
            )
            val auth = path.path("auth") include AuthEndpointsIntegrationTest.TestAuthEndpoints(database, cache)
        }
        val user = AuthEndpointsIntegrationTest.AuthTestUser(email = "a@example.com")
        AuthEndpointsIntegrationTest.AuthTestUser.users[user._id] = user

        authServer.test({}) {
            execute { authServer.passwords.establish(AuthEndpointsIntegrationTest.AuthTestUser, user._id, EstablishPassword("correct horse battery")) }
            val proof = authServer.passwords.prove.test(
                null,
                IdentificationAndPassword("AuthTestUser", "email", "a@example.com", "correct horse battery")
            )

            val response = authServer.auth.login2.test(
                body = TypedData.text(
                    Json.encodeToString(LogInRequest.serializer(), LogInRequest(listOf(proof))),
                    MediaType.Application.Json
                )
            )

            assertEquals(HttpStatus.OK, response.status)
            assertEquals(HttpHeaders { add(HttpHeader.Vary, HttpHeader.Accept) }, response.headers)
            val refresh = Json.parseToJsonElement(response.body!!.text()).jsonObject["refreshToken"]!!.jsonPrimitive.content
            assertTrue(RefreshToken(refresh).valid)
        }
    }

    context(_: TestRunner<*>)
    private suspend fun putInCookie(refresh: RefreshToken): String =
        cookieValue(server.sessions.setRefreshTokenCookie.test(body = jsonBody(refresh.string)).setCookies().single())

    context(_: TestRunner<*>)
    private suspend fun exchange(cookie: String): HttpResponse =
        server.sessions.tokenFromCookie.test(headers = HttpHeaders(HttpHeader.Cookie to "RefreshToken=$cookie"))

    context(_: TestRunner<*>)
    private suspend fun stored(id: Uuid) = execute { server.sessions.sessionInfo.table().get(id) }!!

    private fun reuses() = server.events.received.filterIsInstance<AuthEvent.SessionRejected>()
        .filter { it.reason == AuthEvent.SessionRejected.Reason.Reused }

    context(_: TestRunner<*>)
    private suspend fun next(cookie: String): String = cookieValue(exchange(cookie).setCookies().single())

    @Test
    fun `each cookie exchange returns an access token and replaces the cookie with a new token`() = runBlocking {
        server.test({}, clock = { clock }) {
            val userId = newUser()
            val (session, refresh) = execute { server.sessions.newSession(userId, expires = Instant.parse("2031-01-02T03:04:05Z")) }
            var cookie = putInCookie(refresh)
            assertEquals(time, stored(session._id).rotatedAt)
            val seen = mutableSetOf(refresh.string, cookie)

            repeat(3) {
                time += 1.hours
                val response = exchange(cookie)
                assertEquals(HttpStatus.OK, response.status)
                assertEquals(userId, execute { server.sessions.tokenFormat().read(SessionTestUser, response.stringBody()) }?.id)
                val next = cookieValue(response.setCookies().single())
                assertTrue(next.startsWith("refresh/SessionTestUser/${session._id}:"), next)
                assertTrue(seen.add(next), "a token was issued twice")
                assertEquals(
                    listOf("RefreshToken=$next; Expires=Thu, 2 Jan 2031 03:04:05 GMT; Path=/auth/token/cookie; Secure; HttpOnly; SameSite=Strict"),
                    response.setCookies()
                )
                assertEquals(time, stored(session._id).rotatedAt)
                cookie = next
            }
            assertNull(stored(session._id).terminated)
        }
    }

    @Test
    fun `the previous token within the grace gets the current one back without rotating`() = runBlocking {
        server.test({}, clock = { clock }) {
            val (session, refresh) = execute { server.sessions.newSession(newUser()) }
            val first = putInCookie(refresh)
            val second = next(first)
            val hash = stored(session._id).secretHash

            // Another tab's request, or a retry after the response carrying `second` was lost.
            time += 59.seconds
            val late = exchange(first)
            assertEquals(HttpStatus.OK, late.status)
            assertEquals(listOf(second), late.setCookies().map(::cookieValue))
            assertEquals(hash, stored(session._id).secretHash)
            assertNull(stored(session._id).terminated)
            assertEquals(listOf(), reuses())
        }
    }

    @Test
    fun `the original token stays usable at the cookie endpoint for the grace after set-cookie`() = runBlocking {
        server.test({}, clock = { clock }) {
            val (_, refresh) = execute { server.sessions.newSession(newUser()) }
            val cookie = putInCookie(refresh)

            time += 30.seconds
            val response = exchange(refresh.string)
            assertEquals(HttpStatus.OK, response.status)
            assertEquals(listOf(cookie), response.setCookies().map(::cookieValue))
        }
    }

    @Test
    fun `the previous token after the grace ends the session`() = runBlocking {
        server.test({}, clock = { clock }) {
            val (session, refresh) = execute { server.sessions.newSession(newUser()) }
            val first = putInCookie(refresh)
            val second = next(first)

            time += 60.seconds
            assertEquals(HttpStatus.Unauthorized, exchange(first).status)

            assertNotNull(stored(session._id).terminated)
            assertEquals(listOf(session._id), reuses().map { it.sessionId })
            assertEquals(HttpStatus.Unauthorized, exchange(second).status)
        }
    }

    @Test
    fun `the original token after the grace ends the session, here or at tokenSimple`() = runBlocking {
        server.test({}, clock = { clock }) {
            val (atCookie, cookieRefresh) = execute { server.sessions.newSession(newUser()) }
            putInCookie(cookieRefresh)
            val (atSimple, simpleRefresh) = execute { server.sessions.newSession(newUser()) }
            putInCookie(simpleRefresh)

            time += 61.seconds
            assertEquals(HttpStatus.Unauthorized, exchange(cookieRefresh.string).status)
            assertEquals(HttpStatus.Unauthorized, server.sessions.tokenSimple.test(body = jsonBody(simpleRefresh.string)).status)

            assertNotNull(stored(atCookie._id).terminated)
            assertNotNull(stored(atSimple._id).terminated)
            assertEquals(2, reuses().size)
        }
    }

    @Test
    fun `tokenSimple refuses a rotating session's live tokens without ending it`() = runBlocking {
        server.test({}, clock = { clock }) {
            val (session, refresh) = execute { server.sessions.newSession(newUser()) }
            val cookie = putInCookie(refresh)

            for (token in listOf(refresh.string, cookie))
                assertEquals(HttpStatus.Unauthorized, server.sessions.tokenSimple.test(body = jsonBody(token)).status)

            assertNull(stored(session._id).terminated)
            assertEquals(
                2,
                server.events.received.filterIsInstance<AuthEvent.SessionRejected>()
                    .count { it.reason == AuthEvent.SessionRejected.Reason.RotatingSession }
            )
            assertEquals(HttpStatus.OK, exchange(cookie).status)
        }
    }

    @Test
    fun `a previous token whose current one can't be re-derived is refused without ending the session`() = runBlocking {
        server.test({}, clock = { clock }) {
            val (session, refresh) = execute { server.sessions.newSession(newUser()) }
            putInCookie(refresh)
            // As though the current token had been derived under an earlier secretBasis.
            val current = "derived under another key"
            execute {
                server.sessions.sessionInfo.table().updateOneById(
                    session._id,
                    Modification<Session<SessionTestUser, Uuid>> { it.secretHash assign current.fastHash() },
                )
            }

            assertEquals(HttpStatus.Unauthorized, exchange(refresh.string).status)
            assertNull(stored(session._id).terminated)
            assertEquals(listOf(), reuses())

            // The current token still rotates.
            assertEquals(HttpStatus.OK, exchange(RefreshToken(SessionTestUser.name, session._id, current).string).status)
        }
    }

    @Test
    fun `an older or forged token is refused without ending the session`() = runBlocking {
        server.test({}, clock = { clock }) {
            val (session, refresh) = execute { server.sessions.newSession(newUser()) }
            val first = putInCookie(refresh)
            val second = next(first)
            val third = next(second)
            val forged = RefreshToken(SessionTestUser.name, session._id, "forged").string

            for (token in listOf(refresh.string, first, forged))
                assertEquals(HttpStatus.Unauthorized, exchange(token).status)

            assertNull(stored(session._id).terminated)
            assertEquals(listOf(), reuses())
            assertEquals(HttpStatus.OK, exchange(third).status)
        }
    }

    @Test
    fun `the cookie endpoints refuse a bulk part or a server-side call with a 400, before rotating`() = runBlocking {
        server.test({}) {
            val (session, refresh) = execute { server.sessions.newSession(newUser()) }

            val parts = server.meta.bulk.test(null, mapOf(
                "set" to BulkRequest("/auth/token/set-cookie", "POST", Json.encodeToString(refresh.string)),
                "get" to BulkRequest("/auth/token/cookie", "POST"),
            ))
            for ((name, part) in parts) {
                assertEquals(400, part.error?.http, name)
                assertTrue("directly over HTTP" in part.error!!.message, part.error!!.message)
            }

            assertFailsWith<BadRequestException> { server.sessions.setRefreshTokenCookie.test(null, refresh.string) }
            assertFailsWith<BadRequestException> { server.sessions.tokenFromCookie.test(null, Unit) }

            assertNull(stored(session._id).rotatedAt)
        }
    }
}
