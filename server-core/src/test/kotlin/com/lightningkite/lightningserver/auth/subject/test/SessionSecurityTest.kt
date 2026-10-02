package com.lightningkite.lightningserver.auth.subject.test

import com.lightningkite.UUID
import com.lightningkite.default
import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.auth.AuthOption
import com.lightningkite.lightningserver.auth.AuthType
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.auth.accepts
import com.lightningkite.lightningserver.auth.subject.Session
import com.lightningkite.lightningserver.auth.subject.SubSessionRequest
import com.lightningkite.lightningserver.auth.toRequestRequirements
import com.lightningkite.lightningserver.exceptions.ForbiddenException
import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.testmodels.TestUser
import com.lightningkite.lightningserver.typed.AuthAndPathParts
import com.lightningkite.lightningserver.typed.TypedServerPath0
import com.lightningkite.lightningserver.typed.test
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class SessionSecurityTest {
    private val subject get() = TestSettings.testUserSubject

    private suspend fun login(scopes: Set<String> = setOf("*")): Pair<Session<TestUser, UUID>, RequestAuth<TestUser>> {
        val (session, token) = subject.newSession(TestSettings.testUser.await()._id, scopes = scopes)
        return session to subject.tokenToAuth(token.string, null)!!
    }

    private suspend fun subSession(parent: RequestAuth<TestUser>, request: SubSessionRequest = SubSessionRequest("sub")): RequestAuth<TestUser> {
        val token = subject.createSubSession.implementation(AuthAndPathParts<TestUser, TypedServerPath0>(parent, null, arrayOf()), request)
        return subject.tokenToAuth(token, null)!!
    }

    private fun <T> withClockAdvancedAfter(setup: suspend () -> Unit, action: suspend () -> T): T = runBlocking {
        var time: Instant = Clock.System.now()
        Clock.default = object : Clock {
            override fun now(): Instant = time
        }
        try {
            setup()
            time += 1.hours
            action()
        } finally {
            Clock.default = Clock.System
        }
    }

    @Test
    fun subSessionKeepsAuthenticationTime() {
        lateinit var parent: Session<TestUser, UUID>
        lateinit var parentAuth: RequestAuth<TestUser>
        val recentlyAuthenticated = AuthOption(AuthType<TestUser>(), maxAge = 10.minutes)
        withClockAdvancedAfter({ login().let { parent = it.first; parentAuth = it.second } }) {
            val child = subSession(parentAuth)
            assertEquals(parent.createdAt.toEpochMilliseconds(), child.issuedAt.toEpochMilliseconds())
            assertFalse(recentlyAuthenticated.accepts(child))
        }
    }

    @Test
    fun futureSessionDerivedFromASessionKeepsAuthenticationTime() {
        lateinit var parent: Session<TestUser, UUID>
        val derived = withClockAdvancedAfter({ parent = login().first }) {
            val future = subject.futureSessionToken(parent.subjectId, derivedFrom = parent._id)
            subject.tokenToAuth(subject.openSession.test(null, future), null)!!
        }
        assertEquals(parent.createdAt.toEpochMilliseconds(), derived.issuedAt.toEpochMilliseconds())
    }

    @Test
    fun presignedTokenKeepsAuthenticationTime() {
        lateinit var parent: Session<TestUser, UUID>
        val presigned = withClockAdvancedAfter({ parent = login().first }) {
            subject.tokenToAuth(subject.presignToken(parent, TestSettings.authenticatedGet.toRequestRequirements()), null)!!
        }
        assertEquals(parent.createdAt.toEpochMilliseconds(), presigned.issuedAt.toEpochMilliseconds())
    }

    @Test
    fun subSessionScopesStayWithinParent(): Unit = runBlocking {
        val (_, parentAuth) = login(scopes = setOf("self"))
        assertEquals(setOf("self"), subSession(parentAuth).scopes)
        assertFailsWith<ForbiddenException> { subSession(parentAuth, SubSessionRequest("sub", scopes = setOf("sessions"))) }
        assertFailsWith<ForbiddenException> { subSession(parentAuth, SubSessionRequest("sub", scopes = setOf("self", "sessions"))) }
    }

    @Test
    fun terminatedSessionCannotMintDerivedSessions(): Unit = runBlocking {
        val (parent, parentAuth) = login()
        subject.sessionTerminate.implementation(AuthAndPathParts(parentAuth, null, arrayOf()), Unit)
        assertFailsWith<UnauthorizedException> { subSession(parentAuth) }
        assertFailsWith<UnauthorizedException> {
            subject.openSession.test(null, subject.futureSessionToken(parent.subjectId, derivedFrom = parent._id))
        }
    }

    @Test
    fun terminatingASessionTerminatesDerivedSessions(): Unit = runBlocking {
        val (_, parentAuth) = login()
        val child = subSession(parentAuth)
        val grandchild = subSession(child)
        subject.sessionTerminate.implementation(AuthAndPathParts(parentAuth, null, arrayOf()), Unit)
        val sessions = subject.sessionInfo.collection()
        assertNotNull(sessions.get(child.sessionId!!)!!.terminated)
        assertNotNull(sessions.get(grandchild.sessionId!!)!!.terminated)
    }

    @Test
    fun deletingASessionTerminatesDerivedSessions(): Unit = runBlocking {
        val (parent, parentAuth) = login()
        val child = subSession(parentAuth)
        val sessions = subject.sessionInfo.collection()
        sessions.deleteOneById(parent._id)
        assertNotNull(sessions.get(child.sessionId!!)!!.terminated)
    }
}
