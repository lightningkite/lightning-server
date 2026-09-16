package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.typed.allRegisteredTables
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.database.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

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
        assertFalse("AuditMutation" in tables, "the core dragged in the mutation log")
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

    /**
     * Tampering and query abuse are unrelated investigations with unrelated volumes, so a deployment
     * that wants to know who changed a record should not be made to carry a row per read to get it.
     */
    @Test
    fun `the mutation log can be included alone`() {
        val tables = tablesOf(object : ServerBuilder() {
            val database = setting("database", Database.Settings())
            val audit = path.path("audit") include AuditCore(database)
            val mutations = path.path("mutation") include MutationLog(audit)
        })

        assertTrue("AuditMutation" in tables)
        assertFalse("AuditDataAccess" in tables, "the mutation log pulled in the expensive layer")
        assertFalse("AuditDisclosure" in tables)
    }

    /** Same guard as every other layer: a doubled writer means two rows per change. */
    @Test
    fun `attaching the mutation log twice to one core is refused`() {
        try {
            object : ServerBuilder() {
                val database = setting("database", Database.Settings())
                val audit = path.path("audit") include AuditCore(database)
                val mutations = path.path("mutation") include MutationLog(audit)
                val again = path.path("again") include MutationLog(audit)
            }.build()
            fail("a second MutationLog was accepted, silently doubling every mutation row")
        } catch (e: IllegalStateException) {
            assertTrue("already attached" in (e.message ?: ""), "unhelpful message: ${e.message}")
        }
    }

    /**
     * Attaching a layer twice must fail loudly rather than double every row it writes.
     *
     * Every writer here appends to the builder's interceptor list without deduplication, so a second
     * attachment silently produces two rows per event — and a doubled audit log is worse than a
     * missing one, because it reads as evidence of activity that did not happen. Ordinary public API
     * reaches it: a deployment that installs a layer from a helper of its own and again by hand
     * attaches twice.
     */
    @Test
    fun `attaching a layer twice to one core is refused`() {
        try {
            object : ServerBuilder() {
                val database = setting("database", Database.Settings())
                val audit = path.path("audit") include AuditCore(database)
                val once = path.path("disclosure") include DisclosureLog(audit)
                val again = path.path("again") include DisclosureLog(audit)
            }.build()
            fail("a second DisclosureLog was accepted, silently doubling every disclosure row")
        } catch (e: IllegalStateException) {
            assertTrue("already attached" in (e.message ?: ""), "unhelpful message: ${e.message}")
        }
    }

    /**
     * Installing every layer by hand yields every table, and nothing extra.
     *
     * This replaced a test of the all-in-one bundle, which was cut: a helper that installs
     * everything hides which layers a deployment is actually paying for, and the data access and
     * mutation logs are far too voluminous to acquire by reflex. Each layer is now named at the
     * point of inclusion, which is also what makes the list below reviewable.
     */
    @Test
    fun `installing every layer by hand yields every table`() {
        val tables = tablesOf(object : ServerBuilder() {
            val database = setting("database", Database.Settings())
            val audit = path.path("audit") include AuditCore(database)
            val disclosure = path.path("audit-disclosure") include DisclosureLog(audit)
            val dataAccess = path.path("audit-data-access") include DataAccessLog(audit)
            val authEvents = path.path("audit-auth") include AuthEventLog(audit)
            val mutations = path.path("audit-mutation") include MutationLog(audit)
        })

        assertEquals(
            setOf("AuditRequest", "AuditDisclosure", "AuditDataAccess", "AuditAuthEvent", "AuditMutation"),
            tables.filter { name -> name.startsWith("Audit") && !name.contains("Registration") }.toSet(),
        )
    }
}
