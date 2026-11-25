package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.TestRunner
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.sessions.SessionManagerTest.SessionTestUser
import com.lightningkite.lightningserver.sessions.SessionManagerTest.TestSessionManager
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.SortPart
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.get
import com.lightningkite.services.database.mongodb.MongoDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Rotations of one session's refresh token that read the session row before another rotation writes it.
 *
 * Each race is forced: [ReadGate] holds a rotation right after it has read the row, so it decides on what it read
 * while others write.  Runs on the in-memory database by default; to run it against MongoDB, which the gate then
 * interleaves with real concurrent writes:
 * `./gradlew :sessions:test --tests '*RefreshTokenRotationRaceTest*' -Dlightningserver.test.database=mongodb-test://`
 */
class RefreshTokenRotationRaceTest {
    private val databaseUrl = System.getProperty("lightningserver.test.database") ?: "ram"

    init {
        // Its companion registers the mongodb URL schemes.
        MongoDatabase
    }

    /** Holds the next [hold] reads of the session table, once each has read its rows, until [release]. */
    private class ReadGate {
        val hold = AtomicInteger(0)
        val arrived = Channel<Unit>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
    }

    private val gate = ReadGate()

    private inner class GatedReads<T : Any>(private val wrapped: Table<T>) : Table<T> by wrapped {
        override suspend fun find(
            condition: Condition<T>,
            orderBy: List<SortPart<T>>,
            skip: Int,
            limit: Int,
            maxQueryMs: Long,
        ): Flow<T> {
            val rows = wrapped.find(condition, orderBy, skip, limit, maxQueryMs).toList()
            if (gate.hold.getAndDecrement() > 0) {
                gate.arrived.send(Unit)
                gate.release.await()
            }
            return rows.asFlow()
        }
    }

    private val server = object : ServerBuilder() {
        val database = setting("database", Database.Settings(databaseUrl))
        val cache = setting("cache", Cache.Settings("ram"))
        val sessions = path.path("auth") include TestSessionManager(
            database = database,
            cache = cache,
            sessionSignals = { GatedReads(it) },
        )
        val events = install(object : AuthEventListener {
            val rejections: MutableList<AuthEvent.SessionRejected> = Collections.synchronizedList(mutableListOf())

            context(runtime: ServerRuntime)
            override suspend fun onEvent(event: AuthEvent) {
                if (event is AuthEvent.SessionRejected) rejections += event
            }
        })
    }

    private val request = HttpRequest(
        path = RawHttpEndpoint("/auth/token/cookie", method = HttpMethod.POST),
        queryParameters = QueryParameters.EMPTY,
        headers = HttpHeaders.EMPTY,
        domain = "example.com",
        protocol = "https",
        sourceIp = "203.0.113.7",
    )

    context(_: TestRunner<*>)
    private suspend fun rotate(token: RefreshToken): RefreshToken =
        execute { server.sessions.rotateRefreshToken(token, request) }!!.refreshToken

    context(_: TestRunner<*>)
    private suspend fun stored(id: Uuid) = execute { server.sessions.sessionInfo.table().get(id) }!!

    private suspend fun awaitArrivals(count: Int) = withTimeout(10.seconds) { repeat(count) { gate.arrived.receive() } }

    private fun newUser(): Uuid = Uuid.random().also { SessionTestUser.users[it] = SessionTestUser(it) }

    @Test
    fun `two rotations of the same token that both read before either writes agree on the next token`() = runBlocking {
        server.test({}) {
            val (session, refresh) = execute { server.sessions.newSession(newUser()) }

            gate.hold.set(2)
            val next = coroutineScope {
                val rotations = List(2) { async { rotate(refresh) } }
                awaitArrivals(2)
                gate.release.complete(Unit)
                rotations.awaitAll()
            }

            assertEquals(next[0], next[1])
            assertNull(stored(session._id).terminated)
            assertEquals(listOf(), server.events.rejections)
            assertNotNull(execute { server.sessions.refreshTokenSession(next[0]) })
            rotate(next[0])
        }
    }

    @Test
    fun `a rotation that read the row before two later rotations does not roll the session back`() = runBlocking {
        server.test({}) {
            val (session, t0) = execute { server.sessions.newSession(newUser()) }

            gate.hold.set(1)
            coroutineScope {
                val stale = async { rotate(t0) }
                awaitArrivals(1)
                // While the stale rotation holds what it read, the session moves on twice.
                val t1 = rotate(t0)
                val t2 = rotate(t1)
                gate.release.complete(Unit)
                assertEquals(t1, stale.await())

                // A write by the stale rotation would have put the session back to t1, with t0 as its previous token.
                assertNotNull(execute { server.sessions.refreshTokenSession(t2) })
                assertNull(stored(session._id).terminated)
                assertEquals(listOf(), server.events.rejections)
                rotate(t2)
            }
        }
    }
}
