package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.runtime.Engine
import com.lightningkite.services.data.toSealedMap
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.DatabaseTableDefinition
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.typeParametersSerializersOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * A table that has been registered on a [ServerBuilder] via [registerTable]: its [database], its
 * [tableDefinition], and the [preDeployTask] that reconciles it (creates the collection/indexes)
 * once per deploy.
 *
 * The registration is itself a [Runtime]<[Table]> — invoke it inside an [Engine] to get the
 * live table (`myTable()`). All registrations are enumerable at runtime through
 * [ServerDefinition.allRegisteredTables], keyed by table name.
 */
public data class DatabaseTableRegistration<T : Any>(
    val database: Runtime<Database>,
    val tableDefinition: DatabaseTableDefinition<T>,
    val preDeployTask: PreDeployTask,
) : Runtime<Table<T>> {
    context(server: Engine)
    override fun invoke(): Table<T> = database().table(tableDefinition)
}

public class DatabaseTableRegistry private constructor(
    private val registry: HashMap<String, DatabaseTableRegistration<*>>
): Map<String, DatabaseTableRegistration<*>> by registry {
    public constructor() : this(HashMap())
    public constructor(start: DatabaseTableRegistry) : this(HashMap(start.registry))

    private fun KSerializer<*>.deepEquals(other: KSerializer<*>): Boolean {
        if (this.descriptor != other.descriptor) return false

        val myTypes = this.typeParametersSerializersOrNull()
        val otherTypes = other.typeParametersSerializersOrNull()

        if (myTypes == null && otherTypes == null) return true
        if (myTypes?.size != otherTypes?.size) return false

        return myTypes.orEmpty().zip(otherTypes.orEmpty()).all { (a, b) -> a.deepEquals(b) }
    }

    private fun KSerializer<*>.typeName(): String {
        val types = typeParametersSerializersOrNull()

        return if (types.isNullOrEmpty()) descriptor.serialName
        else descriptor.serialName + types.joinToString(prefix = "<", postfix = ">") { it.typeName() }
    }

    private fun <T : Any> checkRegistered(definition: DatabaseTableDefinition<T>): DatabaseTableRegistration<T>? {
        val existing = registry[definition.name] ?: return null

        if (!existing.tableDefinition.serializer.deepEquals(definition.serializer)) throw DuplicateRegistrationException(
            "Table \"${definition.name}\" is already registered for a different type (${existing.tableDefinition.serializer.typeName()} vs ${definition.serializer.typeName()}).",
            initial = existing.tableDefinition,
            overwrite = definition
        )

        @Suppress("UNCHECKED_CAST")
        return existing as DatabaseTableRegistration<T>
    }

    private fun <T : Any> register(registration: DatabaseTableRegistration<T>) {
        checkRegistered(registration.tableDefinition)
        registry[registration.tableDefinition.name] = registration
    }


    /**
     * Defines a table, registers it on the [builder] (see [ServerDefinition.allRegisteredTables]), and
     * creates its once-per-deploy prepare task. Returns a [DatabaseTableRegistration], which is a runtime
     * accessor for the table — invoke it inside a handler to use it.
     *
     * Idempotent by [definition]: registering the same table again (e.g. a model served by multiple endpoint
     * groups) returns the existing registration rather than creating a duplicate prepare task. Reusing a
     * name for a genuinely *different* table (a different type) throws a [DuplicateRegistrationException]. Table names are unique per server.
     */
    context(builder: ServerBuilder)
    public fun <T : Any> register(database: Runtime<Database>, definition: DatabaseTableDefinition<T>): DatabaseTableRegistration<T> {
        checkRegistered(definition)?.let { return it }
        val task = with(builder) {
            path.path("prepare-${definition.name}") bind PreDeployTask {
                database().prepare(definition)
            }
        }
        val reg = DatabaseTableRegistration(database, definition, task)
        registry[definition.name] = reg
        return reg
    }


    public companion object ExtensionKey : MutableExtensions.WritableKey<DatabaseTableRegistry, Map<String, DatabaseTableRegistration<*>>> {
        override fun default(): DatabaseTableRegistry = DatabaseTableRegistry()

        override fun DatabaseTableRegistry.include(other: Map<String, DatabaseTableRegistration<*>>) {
            other.values.forEach {
                register(it)
            }
        }

        override fun seal(data: Map<String, DatabaseTableRegistration<*>>): Map<String, DatabaseTableRegistration<*>> =
            data.toSealedMap()
    }
}

@PublishedApi
internal val ServerBuilder.tableRegistry: DatabaseTableRegistry by DatabaseTableRegistry

/**
 * Every table registered on this server via [registerTable], keyed by table name.
 *
 * Populated at definition-build time. Enables server-wide functionality that needs to enumerate
 * tables (e.g. preparing or introspecting all of them) without hard-coding the list.
 */
public val ServerDefinition.allRegisteredTables: Map<String, DatabaseTableRegistration<*>> by DatabaseTableRegistry


@Deprecated("It is strongly recommended you define the table name explicitly.")
context(builder: ServerBuilder)
public inline fun <reified T : Any> Runtime<Database>.registerTable(): DatabaseTableRegistration<T> =
    builder.tableRegistry.register(this, DatabaseTableDefinition())

context(builder: ServerBuilder)
public inline fun <reified T : Any> Runtime<Database>.registerTable(name: String): DatabaseTableRegistration<T> =
    builder.tableRegistry.register(this, DatabaseTableDefinition(name))

context(builder: ServerBuilder)
public fun <T : Any> Runtime<Database>.registerTable(name: String, serializer: KSerializer<T>): DatabaseTableRegistration<T> =
    builder.tableRegistry.register(this, DatabaseTableDefinition(serializer, name))