package com.lightningkite.lightningserver.ai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*

/**
 * Creates tools for querying AND modifying a database table through ModelInfo.
 *
 * **WARNING**: These tools can INSERT, UPDATE, and DELETE data. Only use with trusted LLMs
 * and appropriate safeguards.
 *
 * This creates four minimal but powerful tools:
 * - query_{table}(condition, limit, sortBy, descending) - Read records
 * - insert_{table}(record_json) - Insert a single record
 * - update_{table}(condition, modifications_json) - Update matching records
 * - delete_{table}(condition) - Delete matching records
 *
 * @param modelInfo The model info to create tools for
 * @param runtime The server runtime context
 * @return List of tools for this table (4 total)
 */
public fun <SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> createModelInfoToolsWithWrites(
    modelInfo: ModelInfo<SUBJECT, T, ID>,
    runtime: ServerRuntime
): List<SimpleTool<*>> {
    val tableName = modelInfo.tableName

    return listOf(
        QueryTableTool(tableName, modelInfo, runtime),
        InsertTool(tableName, modelInfo, runtime),
        UpdateTool(tableName, modelInfo, runtime),
        DeleteTool(tableName, modelInfo, runtime)
    )
}

/**
 * Tool for inserting a record into a table.
 */
private class InsertTool<SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    private val tableName: String,
    private val modelInfo: ModelInfo<SUBJECT, T, ID>,
    private val runtime: ServerRuntime
) : SimpleTool<InsertTool.Args>() {

    private val table: Table<T>
        get() = with(runtime) { modelInfo.table() }

    override val description: String = """
        Insert a new record into the $tableName table.

        Provide the record as JSON. The record will be validated and inserted.

        Example:
        {"name": "John Doe", "email": "john@example.com", "role": "user"}
    """.trimIndent()

    override val descriptor: ToolDescriptor = ToolDescriptor(
        name = "insert_${tableName.lowercase()}",
        description = description,
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "record_json",
                description = "JSON representation of the record to insert",
                type = ToolParameterType.String
            )
        )
    )

    override val argsSerializer: KSerializer<Args>
        get() = Args.serializer()

    override suspend fun doExecute(args: Args): String {
        return try {
            val jsonElement = Json.parseToJsonElement(args.record_json)
            val record = Json.decodeFromJsonElement(modelInfo.serializer, jsonElement)

            table.insertOne(record)

            val json = Json.encodeToJsonElement(modelInfo.serializer, record).toString()
            "Successfully inserted record into $tableName:\n$json"
        } catch (e: Exception) {
            "Error inserting record: ${e.message}"
        }
    }

    @Serializable
    data class Args(val record_json: String) : Tool.Args
}

/**
 * Tool for updating records in a table.
 */
private class UpdateTool<SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    private val tableName: String,
    private val modelInfo: ModelInfo<SUBJECT, T, ID>,
    private val runtime: ServerRuntime
) : SimpleTool<UpdateTool.Args>() {

    private val table: Table<T>
        get() = with(runtime) { modelInfo.table() }

    private val serializer: KSerializer<T>
        get() = modelInfo.serializer

    override val description: String = """
        Update records in the $tableName table that match a condition.

        The condition parameter uses Lightning Server's Condition format.
        The modifications parameter is a JSON object with field updates.

        Examples:

        Condition (find records to update):
        {"status": {"Equal": "draft"}}

        Modifications (what to change):
        {"status": "published", "publishedAt": "2024-01-15T10:30:00Z"}

        This would update all draft records to published status.
    """.trimIndent()

    override val descriptor: ToolDescriptor = ToolDescriptor(
        name = "update_${tableName.lowercase()}",
        description = description,
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "condition",
                description = "JSON Condition to find records to update",
                type = ToolParameterType.String
            ),
            ToolParameterDescriptor(
                name = "modifications_json",
                description = "JSON object with fields to update",
                type = ToolParameterType.String
            )
        )
    )

    override val argsSerializer: KSerializer<Args>
        get() = Args.serializer()

    override suspend fun doExecute(args: Args): String {
        return try {
            val condition = Json.decodeFromString<Condition<T>>(args.condition)
            val modificationsJson = Json.parseToJsonElement(args.modifications_json)

            // Build modification from JSON
            val modification = parseModification(modificationsJson)

            val count = table.updateMany(condition, modification)
            "Successfully updated $count records in $tableName"
        } catch (e: Exception) {
            "Error updating records: ${e.message}"
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseModification(json: JsonElement): Modification<T> {
        val properties = serializer.serializableProperties
            ?: throw IllegalStateException("Model must be annotated with @GenerateDataClassPaths")

        val jsonObject = json as? JsonObject
            ?: throw IllegalArgumentException("Modifications must be a JSON object")

        val modifications = jsonObject.entries.map { (fieldName, value) ->
            val property = properties.find { it.name == fieldName }
                ?: throw IllegalArgumentException("Field '$fieldName' not found")

            val parsedValue = Json.decodeFromJsonElement(property.serializer as KSerializer<Any?>, value)
            Modification.OnField(property as SerializableProperty<T, Any?>, Modification.Assign(parsedValue))
        }

        return if (modifications.size == 1) {
            modifications.first()
        } else {
            Modification.Chain(modifications)
        }
    }

    @Serializable
    data class Args(
        val condition: String,
        val modifications_json: String
    ) : Tool.Args
}

/**
 * Tool for deleting records from a table.
 */
private class DeleteTool<SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    private val tableName: String,
    private val modelInfo: ModelInfo<SUBJECT, T, ID>,
    private val runtime: ServerRuntime
) : SimpleTool<DeleteTool.Args>() {

    private val table: Table<T>
        get() = with(runtime) { modelInfo.table() }

    override val description: String = """
        Delete records from the $tableName table that match a condition.

        **WARNING**: This permanently deletes data. Use with caution.

        The condition parameter uses Lightning Server's Condition format.

        Examples:

        Delete all inactive users:
        {"active": {"Equal": false}}

        Delete specific record by ID:
        {"_id": {"Equal": "uuid-here"}}

        Delete old records:
        {"createdAt": {"LessThan": "2023-01-01T00:00:00Z"}}
    """.trimIndent()

    override val descriptor: ToolDescriptor = ToolDescriptor(
        name = "delete_from_${tableName.lowercase()}",
        description = description,
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "condition",
                description = "JSON Condition to find records to delete",
                type = ToolParameterType.String
            )
        )
    )

    override val argsSerializer: KSerializer<Args>
        get() = Args.serializer()

    override suspend fun doExecute(args: Args): String {
        return try {
            val condition = Json.decodeFromString<Condition<T>>(args.condition)
            val count = table.deleteMany(condition)
            "Successfully deleted $count records from $tableName"
        } catch (e: Exception) {
            "Error deleting records: ${e.message}"
        }
    }

    @Serializable
    data class Args(val condition: String) : Tool.Args
}
