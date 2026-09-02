package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.typed.allRegisteredTables
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.database.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The layers have to be includable one at a time, not just as a bundle.
 *
 * The reason is volume: the data access log writes a row per *query* rather than per disclosure, so
 * on a read-heavy model it dwarfs everything else here combined. A deployment that wants disclosure
 * auditing should not be made to pay for it, and — since every one of these tables sits on a
 * fail-closed write path — should not be made to depend on it being available either.
 *
 * These assert on the registered tables rather than on behaviour, because "did including this layer
 * drag in another one" is precisely a question about what got registered.
 */
class AuditLayerIndependenceTest {

    private fun tablesOf(server: ServerBuilder) = server.build().allRegisteredTables.keys

    @Test
    fun `the core alone registers only request records and the registry`() {
        val tables = tablesOf(object : ServerBuilder() {
            val database = setting("database", Database.Settings())
            val audit = path.path("audit") include AuditCore(database)
        })

        assertTrue("AuditRequest" in tables)
        assertFalse("AuditDisclosure" in tables, "the core dragged in the disclosure log")
        assertFalse("AuditDataAccess" in tables, "the core dragged in the data access log")
        assertFalse("AuditAuthEvent" in tables, "the core dragged in the auth event log")
    }

    /** The combination a deployment most likely wants: disclosure auditing without the firehose. */
    @Test
    fun `disclosure can be included without the data access log`() {
        val tables = tablesOf(object : ServerBuilder() {
            val database = setting("database", Database.Settings())
            val audit = path.path("audit") include AuditCore(database)
            val disclosures = path.path("disclosure") include DisclosureLog(audit)
        })

        assertTrue("AuditRequest" in tables)
        assertTrue("AuditDisclosure" in tables)
        assertFalse("AuditDataAccess" in tables, "including disclosure pulled in the expensive layer")
    }

    /** Auth events are useful on their own — they answer a question the other layers cannot. */
    @Test
    fun `auth events can be included alone`() {
        val tables = tablesOf(object : ServerBuilder() {
            val database = setting("database", Database.Settings())
            val audit = path.path("audit") include AuditCore(database)
            val authEvents = path.path("auth") include AuthEventLog(audit)
        })

        assertTrue("AuditAuthEvent" in tables)
        assertFalse("AuditDisclosure" in tables)
        assertFalse("AuditDataAccess" in tables)
    }

    /** The bundle still exists, and still means "all of them". */
    @Test
    fun `the bundle includes every layer`() {
        val tables = tablesOf(object : ServerBuilder() {
            val database = setting("database", Database.Settings())
            val audit = path.path("audit") include DisclosureAudit(database)
        })

        assertEquals(
            setOf("AuditRequest", "AuditDisclosure", "AuditDataAccess", "AuditAuthEvent"),
            tables.filter { name -> name.startsWith("Audit") && !name.contains("Registration") }.toSet(),
        )
    }
}
