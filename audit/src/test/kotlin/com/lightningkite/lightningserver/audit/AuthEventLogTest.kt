package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.sessions.AuthEvent
import com.lightningkite.lightningserver.sessions.proofs.ProofMethodInfo
import com.lightningkite.lightningserver.sessions.reportAuthEvent
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** How [AuthEventLogListener] turns each kind of [AuthEvent] into an [AuthEventRecord]. */
class AuthEventLogTest {

    object TestServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())
        val audit = path.path("audit") include AuditCore(database)
        val authEventLog = path.path("audit-auth") include AuthEventLog(audit)
    }

    private fun onServer(block: suspend context(ServerRuntime) (ServerRuntime) -> Unit) = runBlocking {
        TestServer.test(settings = { database set Database.Settings(); cache set Cache.Settings() }) {
            execute { block(serverRuntime, serverRuntime) }
        }
    }

    context(server: ServerRuntime)
    private suspend fun events() = TestServer.authEventLog.authEvents().find(Condition.Always).toList()

    private val password = ProofMethodInfo(via = "password", property = "email")

    @Test
    fun `a session rejection is recorded with its reason and session`() = onServer { runtime ->
        val sessionId = Uuid.random()
        runtime.reportAuthEvent(AuthEvent.SessionRejected(AuthEvent.SessionRejected.Reason.Expired, "user-1", sessionId))

        val event = events().single()
        assertEquals(AuthEventRecord.Type.AuthenticationFailed, event.type)
        assertEquals("user-1", event.principal)
        assertEquals(sessionId, event.sessionId)
        assertEquals("Expired", event.failureReason)
        assertNull(event.method)
        assertEquals(runtime.execution.origin.originId, event.origin)
    }

    @Test
    fun `a token rejection is not recorded`() = onServer {
        serverRuntime.reportAuthEvent(AuthEvent.TokenRejected(AuthEvent.TokenRejected.Reason.NoSuchSession))

        assertEquals(emptyList(), events())
    }

    @Test
    fun `a proof rejection before any account is not recorded`() = onServer {
        serverRuntime.reportAuthEvent(AuthEvent.ProofRejected(password, AuthEvent.ProofRejected.Reason.NoSuchSubject))

        assertEquals(emptyList(), events())
    }

    @Test
    fun `a rejected credential is recorded against its principal`() = onServer {
        serverRuntime.reportAuthEvent(
            AuthEvent.ProofCredentialRejected(password, AuthEvent.ProofCredentialRejected.Reason.SecretMismatch, "user-1")
        )

        val event = events().single()
        assertEquals(AuthEventRecord.Type.ProofRejected, event.type)
        assertEquals("user-1", event.principal)
        assertEquals("SecretMismatch", event.failureReason)
    }

    @Test
    fun `accepted and issued proofs are recorded as different types with no reason`() = onServer {
        serverRuntime.reportAuthEvent(AuthEvent.ProofAccepted(password, principal = "user-1"))
        serverRuntime.reportAuthEvent(AuthEvent.ProofIssued(password, principal = "a@b.c"))

        val events = events().associateBy { it.type }
        assertEquals("user-1", events.getValue(AuthEventRecord.Type.ProofAccepted).principal)
        assertEquals("a@b.c", events.getValue(AuthEventRecord.Type.ProofIssued).principal)
        assertNull(events.getValue(AuthEventRecord.Type.ProofAccepted).failureReason)
        assertNull(events.getValue(AuthEventRecord.Type.ProofIssued).failureReason)
    }
}
