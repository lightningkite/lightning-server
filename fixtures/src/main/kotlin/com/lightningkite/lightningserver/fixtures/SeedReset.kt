package com.lightningkite.lightningserver.fixtures

import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.DatabaseTableRegistration
import com.lightningkite.lightningserver.typed.allRegisteredTables
import com.lightningkite.services.data.References
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.TypedId
import com.lightningkite.services.database._id
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.modules.SerializersModule
import kotlin.uuid.Uuid

/**
 * Deletes every seed row in every registered table, plus every row left pointing at a deleted one
 * (transitively), so that the fixtures can be executed again.  Everything else survives.
 *
 * A seed row is one whose id has a zero top half, as [randomUuid] makes; a real `Uuid.random()` never
 * does, because its version nibble lives there.  References are found through the `@References`
 * annotation on id types, wherever they sit in a row: nested, in sets, nullable.
 *
 * Rows in [noCascade] tables are deleted only if they are seed rows themselves, never for what they
 * point at: a hand-made login keeps its access to seed data, which comes back under the same ids.
 *
 * Loads every table into memory, which is fine at the scales a debug server seeds.  Deletes go to
 * the base tables, past permissions and signals.
 */
@OptIn(ExperimentalSerializationApi::class)
context(runtime: ServerRuntime)
public suspend fun deleteSeedData(noCascade: Set<DatabaseTableRegistration<*>> = emptySet()) {
    if (!generalSettings().debug) throw ForbiddenException("Seed data can only be deleted on a debug server.")

    val tables = runtime.server.allRegisteredTables.values
    val tableFor = tables.associateBy { it.tableDefinition.serializer.descriptor.serialName }
    val rows = tables.associateWith { @Suppress("UNCHECKED_CAST") (it().find(Condition.Always).toList() as List<HasId<*>>) }

    fun isSeedId(id: Any?): Boolean {
        val raw = if (id is TypedId<*, *>) id.raw else id
        return raw is Uuid && raw.toLongs { top, _ -> top == 0L }
    }
    val live = rows.mapValues { (_, list) -> list.mapNotNullTo(HashSet<Any>()) { row -> row._id.takeUnless { isSeedId(it) } } }
    val references = (rows - noCascade).mapValues { (table, list) ->
        @Suppress("UNCHECKED_CAST") val serializer = table.tableDefinition.serializer as KSerializer<Any>
        val module = table.database().context.internalSerializersModule
        list.associate { row -> row._id to ReferenceCollector(module).collect(serializer, row) }
    }

    do {
        var changed = false
        for ((table, byRow) in references) {
            val survivors = live.getValue(table)
            for ((id, targets) in byRow) {
                if (id !in survivors) continue
                val dangling = targets.any { (target, ref) -> tableFor[target]?.let { ref !in live.getValue(it) } ?: false }
                if (dangling) { survivors -= id; changed = true }
            }
        }
    } while (changed)

    for ((table, list) in rows) {
        val doomed = list.map { it._id }.filterTo(HashSet()) { it !in live.getValue(table) }
        if (doomed.isEmpty()) continue
        @Suppress("UNCHECKED_CAST")
        (table() as Table<HasId<Comparable<Any>>>).deleteManyIgnoringOld(
            Condition.OnField(
                (table.tableDefinition.serializer as KSerializer<HasId<Comparable<Any>>>)._id(),
                Condition.Inside(doomed as Set<Comparable<Any>>),
            )
        )
    }
}

/**
 * Lists the `@References` a value holds, as (referenced class's serial name, id), by encoding it and
 * stopping at each referencing id rather than writing anything.
 */
@OptIn(ExperimentalSerializationApi::class)
private class ReferenceCollector(override val serializersModule: SerializersModule) : AbstractEncoder() {
    private val found = ArrayList<Pair<String, Any>>()

    fun <T> collect(serializer: SerializationStrategy<T>, value: T): List<Pair<String, Any>> {
        encodeSerializableValue(serializer, value)
        return found
    }

    override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
        val references = serializer.descriptor.annotations.filterIsInstance<References>().firstOrNull()
            ?: return super.encodeSerializableValue(serializer, value)
        // The nil id is a common way to say "nobody".
        if (value is TypedId<*, *> && value.raw == Uuid.NIL) return
        found += references.references.qualifiedName!! to value!!
    }

    override fun encodeValue(value: Any) {}
    override fun encodeNull() {}
}
