package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.DatabaseTableDefinition
import com.lightningkite.services.database.Table
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * A table that has been registered on a [ServerBuilder] via [registerTable]: its [database], its
 * [tableDefinition], and the [preDeployTask] that reconciles it (creates the collection/indexes)
 * once per deploy.
 *
 * The registration is itself a [Runtime]<[Table]> — invoke it inside a [ServerRuntime] to get the
 * live table (`myTable()`). All registrations are enumerable at runtime through
 * [ServerDefinition.allRegisteredTables], keyed by table name.
 */
public data class DatabaseTableRegistration<T : Any>(
    val database: Runtime<Database>,
    val tableDefinition: DatabaseTableDefinition<T>,
    val preDeployTask: PreDeployTask,
) : Runtime<Table<T>> {
    context(server: ServerRuntime)
    override fun invoke(): Table<T> = database().table(tableDefinition)
}

private fun DatabaseTableRegistration<*>.typeName(): String = tableDefinition.serializer.descriptor.serialName

private object KnownTablesExtensionKey : MapRegistryExtension<String, DatabaseTableRegistration<*>> {
    // The same table is legitimately registered from more than one place (a model served by several
    // endpoint groups, or a module mounted at two paths). Merge those idempotently by name (keep the
    // first) instead of failing; only reject a name reused for a genuinely different table (type).
    override fun MapRegistry<String, DatabaseTableRegistration<*>>.include(other: Map<String, DatabaseTableRegistration<*>>) {
        for ((name, reg) in other) {
            val existing = this[name]
            if (existing == null) register(name, reg)
            else require(existing.typeName() == reg.typeName()) {
                "Table \"$name\" is registered for two different types (${existing.typeName()} vs ${reg.typeName()})."
            }
        }
    }
}

private val ServerBuilder.allRegisteredTables: MapRegistry<String, DatabaseTableRegistration<*>> by KnownTablesExtensionKey

/**
 * Every table registered on this server via [registerTable], keyed by table name.
 *
 * Populated at definition-build time. Enables server-wide functionality that needs to enumerate
 * tables (e.g. preparing or introspecting all of them) without hard-coding the list.
 */
public val ServerDefinition.allRegisteredTables: Map<String, DatabaseTableRegistration<*>> by KnownTablesExtensionKey

@Deprecated("It is strongly recommended you define the table name explicitly.")
context(builder: ServerBuilder)
public inline fun <reified T : Any> Runtime<Database>.registerTable(): DatabaseTableRegistration<T> =
    registerTable(T::class.simpleName!!, serializer<T>())

context(builder: ServerBuilder)
public inline fun <reified T : Any> Runtime<Database>.registerTable(name: String): DatabaseTableRegistration<T> =
    registerTable(name, serializer<T>())

/**
 * Defines a table, registers it on the [builder] (see [ServerDefinition.allRegisteredTables]), and
 * creates its once-per-deploy prepare task. Returns a [DatabaseTableRegistration], which is a runtime
 * accessor for the table — invoke it inside a handler to use it.
 *
 * Idempotent by [name]: registering the same table again (e.g. a model served by multiple endpoint
 * groups) returns the existing registration rather than creating a duplicate prepare task. Reusing a
 * name for a genuinely *different* table (a different type) throws. Table names are unique per server.
 */
context(builder: ServerBuilder)
public fun <T : Any> Runtime<Database>.registerTable(
    name: String,
    serializer: KSerializer<T>,
): DatabaseTableRegistration<T> {
    builder.allRegisteredTables[name]?.let { existing ->
        require(existing.typeName() == serializer.descriptor.serialName) {
            "Table \"$name\" is already registered for a different type (${existing.typeName()} vs ${serializer.descriptor.serialName})."
        }
        @Suppress("UNCHECKED_CAST")
        return existing as DatabaseTableRegistration<T>
    }
    val def = DatabaseTableDefinition(serializer, name)
    val task = with(builder) {
        path.path("prepare-$name") bind PreDeployTask {
            this@registerTable().prepare(def)
            Unit
        }
    }
    val reg = DatabaseTableRegistration(this@registerTable, def, task)
    builder.allRegisteredTables.register(name, reg)
    return reg
}
