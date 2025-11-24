package com.lightningkite.lightningserver.ai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.KSerializer

/**
 * Creates tools for querying a database table through ModelInfo.
 *
 * This creates four tools for the given table:
 * - get_{table}_by_id(id: ID) - Get a single record by ID
 * - count_{table}(condition: Condition) - Count records in the table that match the condition
 * - query_{table}(condition: Condition, orderBy: List<SortPart>, skip: Int, limit: Int) - Advanced queries
 * - aggregate_query_{table}(aggregate: Aggregate, condition: Condition, property: DataClassPathPartial) - Aggregate Queries
 *
 * @param modelInfo The model info to create tools for
 * @param authAccess The auth for the client making the request
 * @param queryLimit The max size for the query limit
 * @param runtime The server runtime context
 * @return List of tools for this table
 */
public fun <SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> createModelInfoTools(
    modelInfo: ModelInfo<SUBJECT, T, ID>,
    authAccess: AuthAccess<SUBJECT>,
    queryLimit: Int,
    runtime: ServerRuntime,
): List<SimpleTool<*>> = listOf(
    GetByIdTool(modelInfo, authAccess, runtime),
    CountTableTool(modelInfo, authAccess, runtime),
    QueryTableTool(modelInfo, authAccess, queryLimit, runtime),
    AggregateQueryTableTool(modelInfo, authAccess, runtime),
)

/**
 * Tool for counting records in a table.
 */
public class CountTableTool<SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    private val modelInfo: ModelInfo<SUBJECT, T, ID>,
    private val authAccess: AuthAccess<SUBJECT>,
    private val runtime: ServerRuntime,
) : SimpleTool<Condition<T>>() {

    override val name: String = "count_${modelInfo.tableName.lowercase()}"

    override val description: String =
        "Count the total number of records in the ${modelInfo.tableName} table that match the given condition"

    override val argsSerializer: KSerializer<Condition<T>> = ConditionSerializer(modelInfo.serializer)

    override val descriptor: ToolDescriptor
        get() = argsSerializer.descriptor.lsAsToolDescriptor(
            name,
            description,
            maxDepth = 1
        )

    override suspend fun doExecute(args: Condition<T>): String {
        val count = with(runtime) { modelInfo.table(authAccess) }.count(args)
        return "Found $count records in the ${modelInfo.tableName} table."
    }
}

/**
 * Tool for getting a record by ID.
 */
public class GetByIdTool<SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    private val modelInfo: ModelInfo<SUBJECT, T, ID>,
    private val authAccess: AuthAccess<SUBJECT>,
    private val runtime: ServerRuntime,
) : SimpleTool<ID>() {

    override val name: String = "get_${modelInfo.tableName.lowercase()}_by_id"

    override val description: String = "Get a single record from the ${modelInfo.tableName} table by its ID"

    override val argsSerializer: KSerializer<ID> = modelInfo.idSerializer

    override suspend fun doExecute(args: ID): String {
        val record = with(runtime) { modelInfo.table(authAccess) }.get(args)

        return if (record != null) {
            val json = with(runtime) {
                externalSerialization.json.encodeToString(modelInfo.serializer, record)
            }
            "Found record:\n$json"
        } else {
            "No record found with ID: ${args}"
        }
    }
}


/**
 * Advanced query tool that allows complex database queries using Lightning Server's Condition format.
 *
 * This tool accepts a Condition specification that can include:
 * - Field comparisons (Equal, NotEqual, GreaterThan, LessThan, etc.)
 * - Multiple conditions with And/Or logic
 * - String operations (StringContains, etc.)
 * - Set operations (Inside, NotInside)
 */
public class QueryTableTool<SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    private val modelInfo: ModelInfo<SUBJECT, T, ID>,
    private val authAccess: AuthAccess<SUBJECT>,
    private val queryLimit: Int,
    private val runtime: ServerRuntime,
) : SimpleTool<Query<T>>() {

    override val name: String = "query_${modelInfo.tableName.lowercase()}"

    override val description: String = """
        Query the ${modelInfo.tableName} table with advanced filters and optional sorting.

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
        - orderBy: List of SortPart specifications (default: [] - database-dependent ordering)
        - limit: Maximum number of results (default 100)
        - skip: Number of records to skip for pagination (default 0)
    """.trimIndent()

    override val argsSerializer: KSerializer<Query<T>> = Query.serializer(modelInfo.serializer)

    override val descriptor: ToolDescriptor
        get() = argsSerializer.descriptor.lsAsToolDescriptor(
            name,
            description,
            maxDepth = 1
        )

    override suspend fun doExecute(args: Query<T>): String {

        if (args.limit > queryLimit) return "Error Querying Records. Max limit: $queryLimit"

        // Execute the query
        val results = with(runtime) { modelInfo.table(authAccess) }
            .find(
                condition = args.condition,
                orderBy = args.orderBy,
                skip = args.skip,
                limit = args.limit,
            )
            .toList()

        return if (results.isEmpty()) {
            "No records found matching the query criteria in the ${modelInfo.tableName} table."
        } else {
            val json = with(runtime) {
                externalSerialization.json.encodeToString(results)
            }
            "Found ${results.size} records:\n$json"
        }
    }
}


/**
 * Advanced Aggregate Query tool that allows complex database aggregates using Lightning Server's Condition format.
 *
 * This tool accepts a Condition specification that can include:
 * - Field comparisons (Equal, NotEqual, GreaterThan, LessThan, etc.)
 * - Multiple conditions with And/Or logic
 * - String operations (StringContains, etc.)
 * - Set operations (Inside, NotInside)
 */
public class AggregateQueryTableTool<SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    private val modelInfo: ModelInfo<SUBJECT, T, ID>,
    private val authAccess: AuthAccess<SUBJECT>,
    private val runtime: ServerRuntime,
) : SimpleTool<AggregateQuery<T>>() {

    override val description: String = """
        Aggregate Query the ${modelInfo.tableName} table with advanced filters. It works on Number type fields only.

        The aggregate parameter is an Aggregate enum with the values: Sum, Average, StandardDeviationSample, and StandardDeviationPopulation.
        
        The property parameter is the model field for which to run the aggregate on.
        
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
    """.trimIndent()

    override val name: String = "aggregate_${modelInfo.tableName.lowercase()}"

    override val argsSerializer: KSerializer<AggregateQuery<T>> = AggregateQuery.serializer(modelInfo.serializer)

    override val descriptor: ToolDescriptor
        get() = argsSerializer.descriptor.lsAsToolDescriptor(
            name,
            description,
            maxDepth = 1
        )

    override suspend fun doExecute(args: AggregateQuery<T>): String {

        // Execute the query
        @Suppress("UNCHECKED_CAST")
        val result = with(runtime) { modelInfo.table(authAccess) }.aggregate(
            aggregate = args.aggregate,
            condition = args.condition,
            property = args.property as DataClassPath<T, Number>
        )

        return if (result == null) {
            "No records found matching the query criteria in the ${modelInfo.tableName} table."
        } else {
            "Found value : $result"
        }
    }

}
