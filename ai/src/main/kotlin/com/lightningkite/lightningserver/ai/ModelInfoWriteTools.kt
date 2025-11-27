package com.lightningkite.lightningserver.ai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.services.database.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Creates tools for modifying a database table through ModelInfo.
 *
 * **WARNING**: These tools can INSERT, UPDATE, and DELETE data. Only use with trusted LLMs
 * and appropriate safeguards.
 *
 * This creates four minimal but powerful tools:
 * - insert_{table}(records) - Insert a set of records
 * - update_{table}(ids, modification) - Update a set of records by _id
 * - delete_{table}(ids) - Delete a set of records by _id
 *
 * @param modelInfo The model info to create tools for
 * @param authAccess The auth for the client making the request
 * @param writeLimit The hard limit for how many items can be inserted or modified at once.
 * @param runtime The server runtime context
 * @return List of tools for this table (4 total)
 */
public fun <SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> createModelInfoToolsWithWrites(
    modelInfo: ModelInfo<SUBJECT, T, ID>,
    authAccess: AuthAccess<SUBJECT>,
    writeLimit: Int,
    modelExamples: List<T>,
    runtime: ServerRuntime,
): List<SimpleTool<*>> = listOf(
    InsertTool(modelInfo, authAccess, writeLimit, modelExamples, runtime),
    UpdateTool(modelInfo, authAccess, writeLimit, runtime),
    DeleteTool(modelInfo, authAccess, writeLimit, runtime)
)

/**
 * Tool for inserting a record into a table.
 */
public class InsertTool<SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    private val modelInfo: ModelInfo<SUBJECT, T, ID>,
    private val authAccess: AuthAccess<SUBJECT>,
    private val limit: Int,
    private val modelExamples: List<T>,
    private val runtime: ServerRuntime,
) : LsSimpleTool<List<T>>(runtime.externalSerialization.serializersModule) {

    override val description: String = """
        Insert records into the ${modelInfo.tableName} table.

        Provide the list of records. The records will be validated and inserted. (Max size $limit)
    ${
        if (modelExamples.isNotEmpty()) {
            """
                
            Example:
            ${
                with(runtime) { externalSerialization.json.encodeToString(modelExamples) }
            }  
            """.trimIndent()
        } else ""
    }
        
    """.trimIndent()

    override val name: String = "insert_${modelInfo.tableName.lowercase()}"

    override val argsSerializer: KSerializer<List<T>> = ListSerializer(modelInfo.serializer)

    override suspend fun doExecute(args: List<T>): String {
        return try {
            if (args.size > limit) return "Error inserting records. Records list is too large. Max $limit records allowed"

            val results = with(runtime) { modelInfo.table(authAccess) }.insertMany(args)

            if (results.isEmpty())
                "Failed to insert any records"
            else {
                val json = with(runtime) { externalSerialization.json.encodeToString(results) }
                "Successfully inserted records into ${modelInfo.tableName}: $json"
            }
        } catch (e: Exception) {
            "Error inserting records: ${e.message}"
        }
    }

}

/**
 * Tool for updating records in a table.
 */
public class UpdateTool<SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    private val modelInfo: ModelInfo<SUBJECT, T, ID>,
    private val authAccess: AuthAccess<SUBJECT>,
    private val limit: Int,
    private val runtime: ServerRuntime,
) : LsSimpleTool<UpdateTool.Args<T, ID>>(runtime.externalSerialization.serializersModule) {

    override val description: String = """
        Update records in the ${modelInfo.tableName} table that match a condition.

        The ids parameter is a Json List of data IDs (Max size $limit).
        The modification parameter uses Lightning Server's Modification format.

        Examples:

        IDs (What records to update):
        [10, 12, 132, 444]

        Modification (what to change):
        {
            "Chain": [ 
                {"status": { "Assign": "published" }}, 
                { "publishedAt": { "Assign": "2024-01-15T10:30:00Z" }}
            ]   
        }

        This would update all records with ids inside the ids parameter to have a published status.
    """.trimIndent()

    override val name: String = "update_${modelInfo.tableName.lowercase()}"

    override val argsSerializer: KSerializer<Args<T, ID>> = Args.serializer(modelInfo.serializer, modelInfo.idSerializer)

    override suspend fun doExecute(args: Args<T, ID>): String {
        return try {

            if (args.ids.size > limit) return "Error Updating Records. ids list is too large. Max $limit ids allowed"

            val updateResults = with(runtime) { modelInfo.table(authAccess) }
                .updateMany(
                    Condition.OnField(modelInfo.serializer._id(), Condition.Inside(args.ids)),
                    args.modification.modification
                )
                .changes
                .mapNotNull { it.new }

            if (updateResults.isEmpty())
                "Failed to update any records"
            else {
                val json = with(runtime) { externalSerialization.json.encodeToString(updateResults) }
                "Successfully updated records from ${modelInfo.tableName}: $json"
            }
        } catch (e: Exception) {
            "Error updating records: ${e.message}"
        }
    }


    @Serializable
    public data class Args<T, ID>(
        val ids: List<ID>,
        val modification: ModificationExpression<T>,
    )
}

/**
 * Tool for deleting records from a table.
 */
public class DeleteTool<SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    private val modelInfo: ModelInfo<SUBJECT, T, ID>,
    private val authAccess: AuthAccess<SUBJECT>,
    private val limit: Int,
    private val runtime: ServerRuntime,
) : LsSimpleTool<List<ID>>(runtime.externalSerialization.serializersModule) {

    override val description: String = """
        Delete records from the ${modelInfo.tableName} table that have the provided ids.

        **WARNING**: This permanently deletes data. Use with caution.

        The ids parameter is a Json List of data IDs (Max size $limit).

        Examples:

        A List of UUIDs:
        ["b06e0732-b3a9-492c-90c3-8e34ba568c73", "4ff3b348-a528-4a15-afcb-1325b3a4e1f1"]

        A List of Integers:
        [1, 12, 22, 25]

    """.trimIndent()

    override val argsSerializer: KSerializer<List<ID>> = ListSerializer(modelInfo.idSerializer)

    override suspend fun doExecute(args: List<ID>): String {
        return try {

            if (args.size > limit) return "Error Updating Records. ids list is too large. Max $limit ids allowed"

            val deletedResults = with(runtime) { modelInfo.table(authAccess) }
                .deleteMany(Condition.OnField(modelInfo.serializer._id(), Condition.Inside(args)))
            if (deletedResults.isEmpty())
                "Failed to delete any records"
            else {
                val json = with(runtime) { externalSerialization.json.encodeToString(deletedResults) }
                "Successfully deleted records from ${modelInfo.tableName}: $json"
            }
        } catch (e: Exception) {
            "Error deleting records: ${e.message}"
        }
    }

}
