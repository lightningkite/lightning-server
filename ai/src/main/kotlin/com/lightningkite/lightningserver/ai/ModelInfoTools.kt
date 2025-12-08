package com.lightningkite.lightningserver.ai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.condition
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Creates tools for querying a database table through ModelInfo.
 *
 * This creates four tools for the given table:
 * - count_{table}() - Count total records in the table
 * - get_{table}_by_id(id: String) - Get a single record by ID
 * - list_{table}(limit: Int) - List recent records from the table
 * - query_{table}(condition: String, limit: Int?, sortBy: String?, descending: Boolean?) - Advanced queries
 *
 * @param modelInfo The model info to create tools for
 * @param runtime The server runtime context
 * @return List of tools for this table
 */
public fun <SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> createModelInfoTools(
    modelInfo: ModelInfo<SUBJECT, T, ID>,
    runtime: ServerRuntime
): List<SimpleTool<*>> {
    val tableName = modelInfo.tableName

    return listOf(
        CountTableTool(tableName, modelInfo, runtime),
        GetByIdTool(tableName, modelInfo, runtime),
        ListRecentTool(tableName, modelInfo, runtime),
        QueryTableTool(tableName, modelInfo, runtime)
    )
}

/**
 * Tool for counting records in a table.
 */
private class CountTableTool<SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    private val tableName: String,
    private val modelInfo: ModelInfo<SUBJECT, T, ID>,
    private val runtime: ServerRuntime
) : SimpleTool<CountTableTool.Args>() {

    private val table: Table<T>
        get() = with(runtime) { modelInfo.table() }

    override val description: String = "Count the total number of records in the $tableName table"

    override val descriptor: ToolDescriptor = ToolDescriptor(
        name = "count_${tableName.lowercase()}",
        description = description,
        requiredParameters = listOf()
    )

    override val argsSerializer: KSerializer<Args>
        get() = Args.serializer()

    override suspend fun doExecute(args: Args): String {
        val count = table.count()
        return "There are $count records in the $tableName table."
    }

    @Serializable
    data class Args(val dummy: String = "") : Tool.Args
}

/**
 * Tool for getting a record by ID.
 */
private class GetByIdTool<SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    private val tableName: String,
    private val modelInfo: ModelInfo<SUBJECT, T, ID>,
    private val runtime: ServerRuntime
) : SimpleTool<GetByIdTool.Args>() {

    private val table: Table<T>
        get() = with(runtime) { modelInfo.table() }

    override val description: String = "Get a single record from the $tableName table by its ID"

    override val descriptor: ToolDescriptor = ToolDescriptor(
        name = "get_${tableName.lowercase()}_by_id",
        description = description,
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "id",
                description = "The ID of the record to retrieve",
                type = ToolParameterType.String
            )
        )
    )

    override val argsSerializer: KSerializer<Args>
        get() = Args.serializer()

    override suspend fun doExecute(args: Args): String {
        // Get all records and filter by ID
        // This is not optimal but works for the get-by-id use case
        @Suppress("UNCHECKED_CAST")
        val id = args.id as ID

        val record = table.find(Condition.Always).firstOrNull { it._id == id }
        return if (record != null) {
            val json = Json.encodeToJsonElement(modelInfo.serializer, record).toString()
            "Found record:\n$json"
        } else {
            "No record found with ID: ${args.id}"
        }
    }

    @Serializable
    data class Args(val id: String) : Tool.Args
}

/**
 * Tool for listing recent records.
 */
private class ListRecentTool<SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    private val tableName: String,
    private val modelInfo: ModelInfo<SUBJECT, T, ID>,
    private val runtime: ServerRuntime
) : SimpleTool<ListRecentTool.Args>() {

    private val table: Table<T>
        get() = with(runtime) { modelInfo.table() }

    override val description: String = "List recent records from the $tableName table"

    override val descriptor: ToolDescriptor = ToolDescriptor(
        name = "list_${tableName.lowercase()}",
        description = description,
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "limit",
                description = "Maximum number of records to return (default 10, max 100)",
                type = ToolParameterType.Integer
            )
        )
    )

    override val argsSerializer: KSerializer<Args>
        get() = Args.serializer()

    override suspend fun doExecute(args: Args): String {
        val limit = args.limit.coerceIn(1, 100)
        val records = table.find(condition = Condition.Always, limit = limit).toList()

        return if (records.isEmpty()) {
            "No records found in the $tableName table."
        } else {
            val json = records.joinToString(",\n", "[\n", "\n]") {
                Json.encodeToJsonElement(modelInfo.serializer, it).toString()
            }
            "Found ${records.size} records:\n$json"
        }
    }

    @Serializable
    data class Args(val limit: Int = 10) : Tool.Args
}
