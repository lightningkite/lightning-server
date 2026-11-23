package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.services.database.*
import com.lightningkite.services.database.optics.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ModelRestTest {
    @Test fun `generated endpoints insert get query modify delete`() { modelRestTest() }
    @Test fun `custom endpoint uses permissioned table and writes are refused`() { modelRestCustomTest() }

    // Keeps the "Conditions and modifications as JSON" section of model-rest.md honest.
    @Test fun `conditions and modifications serialize as documented`() = NoteDbServer.testBlocking(settings = {}) {
        val json = engine.externalSerialization.json
        val conditions = Condition.serializer(Note.serializer())
        val modifications = Modification.serializer(Note.serializer())

        assertEquals(
            """{"And":[{"views":{"GreaterThanOrEqual":10}},{"tags":{"CollectionAnyElements":{"Equal":"home"}}},{"Not":{"title":{"StringContains":{"value":"draft","ignoreCase":true}}}}]}""",
            json.encodeToString(conditions, Condition.And(listOf(Note.views gte 10, Note.tags.any { it eq "home" }, !(Note.title contains "draft")))),
        )
        assertEquals(
            """{"archivedAt":{"IfNotNull":{"LessThan":"2026-01-01T00:00:00Z"}}}""",
            json.encodeToString(conditions, Note.archivedAt.notNull lt Instant.parse("2026-01-01T00:00:00Z")),
        )
        assertEquals(
            """{"Chain":[{"title":{"Assign":"Groceries"}},{"views":{"Increment":1}},{"tags":{"SetRemoveInstances":["home"]}}]}""",
            json.encodeToString(modifications, Modification {
                it.title assign "Groceries"
                it.views += 1
                it.tags -= "home"
            }),
        )
        assertEquals("""{"Always":true}""", json.encodeToString(conditions, Condition.Always))
        assertEquals("""{"archivedAt":{"Equal":null}}""", json.encodeToString(conditions, Note.archivedAt eq null))
        assertEquals("""{"title":{"Inside":["a","b"]}}""", json.encodeToString(conditions, Note.title inside listOf("a", "b")))
        assertEquals("""{"tags":{"SetRemove":{"Equal":"a"}}}""", json.encodeToString(modifications, Modification { it.tags.removeAll { t -> t eq "a" } }))
    }
}
