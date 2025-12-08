package com.lightningkite.lightningserver.ai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.DataClassPathPartial
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.SortPart
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.serializableProperties
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Advanced query tool that allows complex database queries using Lightning Server's Condition format.
 *
 * This tool accepts a JSON Condition specification that can include:
 * - Field comparisons (Equal, NotEqual, GreaterThan, LessThan, etc.)
 * - Multiple conditions with And/Or logic
 * - String operations (StringContains, etc.)
 * - Set operations (Inside, NotInside)
 */
internal class QueryTableTool<SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    private val tableName: String,
    private val modelInfo: ModelInfo<SUBJECT, T, ID>,
    private val runtime: ServerRuntime
) : SimpleTool<QueryTableTool.Args>() {

    private val table: Table<T>
        get() = with(runtime) { modelInfo.table() }

    private val serializer: KSerializer<T>
        get() = modelInfo.serializer

    override val description: String = """
        Query the $tableName table with advanced filters and optional sorting.

        The condition parameter uses Lightning Server's Condition format. Examples:

        Simple equality:
        {"fieldName": {"Equal": "value"}}

        Greater than:
        {"age": {"GreaterThan": 18}}

        String contains (case-insensitive):
        {"name": {"StringContains": {"value": "John", "ignoreCase": true}}}

        Multiple conditions with AND:
        {"And": [{"role": {"Equal": "admin"}}, {"active": {"Equal": true}}]}

        Multiple conditions with OR:
        {"Or": [{"status": {"Equal": "active"}}, {"status": {"Equal": "pending"}}]}

        Complex nested query:
        {"And": [{"Or": [{"role": {"Equal": "admin"}}, {"role": {"Equal": "moderator"}}]}, {"active": {"Equal": true}}]}

        Match all records:
        {"Always": true}

        Available operators: Equal, NotEqual, GreaterThan, LessThan, GreaterThanOrEqual, LessThanOrEqual,
        Inside, NotInside, StringContains, RegexMatches

        Optional parameters:
        - limit: Maximum number of results (default 10, max 100)
        - sortBy: Field name to sort by (optional)
        - descending: Sort in descending order (default false)
    """.trimIndent()

    override val descriptor: ToolDescriptor = ToolDescriptor(
        name = "query_${tableName.lowercase()}",
        description = description,
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "condition",
                description = "JSON Condition specification using Lightning Server format",
                type = ToolParameterType.String
            )
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                name = "limit",
                description = "Maximum number of results to return (default 10, max 100)",
                type = ToolParameterType.Integer
            ),
            ToolParameterDescriptor(
                name = "sortBy",
                description = "Field name to sort results by",
                type = ToolParameterType.String
            ),
            ToolParameterDescriptor(
                name = "descending",
                description = "Sort in descending order (default false)",
                type = ToolParameterType.Boolean
            )
        )
    )

    override val argsSerializer: KSerializer<Args>
        get() = Args.serializer()

    override suspend fun doExecute(args: Args): String {
        return try {
            // Deserialize the Condition directly
            val condition = Json.decodeFromString<Condition<T>>(args.condition)

            // Handle sorting if requested
            val sortPart = args.sortBy?.let { fieldName ->
                val properties = serializer.serializableProperties
                    ?: throw IllegalStateException("Model must be annotated with @GenerateDataClassPaths")
                val property = properties.find { it.name == fieldName }
                    ?: throw IllegalArgumentException("Field '$fieldName' not found")

                @Suppress("UNCHECKED_CAST")
                SortPart(property as DataClassPathPartial<T>, args.descending ?: false)
            }

            // Execute the query
            val results = table.find(
                condition = condition,
                orderBy = listOfNotNull(sortPart),
                limit = args.limit?.coerceIn(1, 100) ?: 10
            ).toList()

            if (results.isEmpty()) {
                "No records found matching the query criteria in the $tableName table."
            } else {
                val json = results.joinToString(",\n", "[\n", "\n]") {
                    Json.encodeToJsonElement(serializer, it).toString()
                }
                "Found ${results.size} records:\n$json"
            }
        } catch (e: Exception) {
            "Error executing query: ${e.message}\n\nPlease check the Condition format. Example: {\"status\": {\"Equal\": \"active\"}}"
        }
    }

    @Serializable
    data class Args(
        val condition: String,
        val limit: Int? = null,
        val sortBy: String? = null,
        val descending: Boolean? = null
    ) : Tool.Args
}
