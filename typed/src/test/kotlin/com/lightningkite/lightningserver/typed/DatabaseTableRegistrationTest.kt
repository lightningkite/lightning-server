package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.definition.builder.DuplicateRegistrationException
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.services.database.*
import kotlinx.serialization.Serializable
import kotlin.test.*
import kotlin.uuid.Uuid

class DatabaseTableRegistrationTest {

    @Serializable
    data class RegNote(override val _id: Uuid = Uuid.random(), val title: String = "") : HasId<Uuid>

    @Serializable
    data class RegOther(override val _id: Uuid = Uuid.random()) : HasId<Uuid>

    @Test
    fun `registerTable is idempotent by name and enumerable at runtime`() {
        val server = object : ServerBuilder() {
            val database = setting("database", Database.Settings())
            val a = database.registerTable<RegNote>("RegNote")
            val b = database.registerTable<RegNote>("RegNote") // same name + type
        }
        // Second registration returns the first — no duplicate prepare task, shared safely.
        assertSame(server.a, server.b)

        val definition = server.build()
        assertEquals(setOf("RegNote"), definition.allRegisteredTables.keys)
        assertEquals("RegNote", definition.allRegisteredTables.getValue("RegNote").tableDefinition.name)
    }

    @Test
    fun `registerTable rejects a name reused for a different type`() {
        assertFailsWith<DuplicateRegistrationException> {
            object : ServerBuilder() {
                val database = setting("database", Database.Settings())
                val a = database.registerTable<RegNote>("Shared")
                val b = database.registerTable<RegOther>("Shared") // same name, different type
            }
        }
    }

    @Test
    fun `the same table registered across modules merges to one entry`() {
        val moduleA = object : ServerBuilder() {
            val database = setting("database", Database.Settings())
            val note = database.registerTable<RegNote>("RegNote")
        }
        val moduleB = object : ServerBuilder() {
            val database = setting("database", Database.Settings())
            val note = database.registerTable<RegNote>("RegNote") // same table, another module
            val other = database.registerTable<RegOther>("RegOther")
        }
        val root = object : ServerBuilder() {
            val a = path.path("a") include moduleA
            val b = path.path("b") include moduleB
        }
        // RegNote appears in both modules but merges to one entry (idempotent by name).
        assertEquals(setOf("RegNote", "RegOther"), root.build().allRegisteredTables.keys)
    }
}
