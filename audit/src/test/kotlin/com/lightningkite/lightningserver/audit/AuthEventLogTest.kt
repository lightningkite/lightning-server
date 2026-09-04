package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The auth event log is reached through a reporter installed on the server, because an authentication
 * failure touches nothing the other layers observe: the login endpoints are `noAuth` and throw before
 * any authentication resolves, so the access log cannot see them, and nothing is written to a table.
 */
class AuthEventLogTest {

    object TestServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())
        val audit = path.path("audit") include AuditCore(database)
        val authEventLog = path.path("audit-auth") include AuthEventLog(audit)
    }

    private fun onServer(block: suspend context(ServerRuntime) (ServerRuntime) -> Unit) = runBlocking {
        TestServer.test(settings = { database set Database.Settings(); cache set Cache.Settings() }) {
            block(serverRuntime, serverRuntime)
        }
    }

    context(server: ServerRuntime)
    private suspend fun events() = TestServer.authEventLog.authEvents().find(Condition.Always).toList()

    @Test
    fun `a reported failure becomes a queryable record`() = onServer { runtime ->
        runtime.server.authEventReporters.single().report(
            type = "AuthenticationFailed",
            principal = "user-1",
            detail = "SecretMismatch",
        )

        val event = events().single()
        assertEquals(AuthEventType.AuthenticationFailed, event.type)
        assertEquals("user-1", event.principal)
        assertEquals("SecretMismatch", event.failureReason)
        assertEquals(runtime.initiator.requestRecordId, event.requestId)
    }

    /**
     * A reporter must not throw: it is called from paths that are already rejecting something, and a
     * second failure there would replace a clean rejection with an unrelated error and lose the
     * original reason. An unknown type is logged and dropped rather than raised.
     */
    @Test
    fun `an unrecognised event type is dropped, not thrown`() = onServer { runtime ->
        runtime.server.authEventReporters.single().report(type = "NotAnEventType")

        assertEquals(emptyList(), events())
    }

    /** Events join to the same request record as the disclosures made under the resulting session. */
    @Test
    fun `an actor is recorded separately from the subject`() = onServer { runtime ->
        runtime.server.authEventReporters.single().report(
            type = "SessionTerminated",
            principal = "victim",
            actor = "administrator",
        )

        val event = events().single()
        assertEquals("victim", event.principal)
        assertEquals("administrator", event.actor)
    }

}
