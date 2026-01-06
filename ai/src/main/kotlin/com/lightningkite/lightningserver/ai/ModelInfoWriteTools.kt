package com.lightningkite.lightningserver.ai

import ai.koog.agents.core.tools.SimpleTool
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.services.database.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import com.lightningkite.lightningserver.ai.models.*

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
 * @param this@createModelInfoToolsWithWrites The model info to create tools for
 * @param authAccess The auth for the client making the request
 * @param writeLimit The hard limit for how many items can be inserted or modified at once.
 * @param runtime The server runtime context
 * @return List of tools for this table (4 total)
 */
public fun <SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> ModelInfo<SUBJECT, T, ID>.writeTools(
    writeLimit: Int,
    modelExamples: List<T>,
): List<ChatTool<SUBJECT, *>> = listOf(
    InsertTool(this, writeLimit, modelExamples),
    UpdateTool(this, writeLimit),
    DeleteTool(this, writeLimit)
)

/**
 * Tool for inserting a record into a table.
 */
public class InsertTool<SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    private val modelInfo: ModelInfo<SUBJECT, T, ID>,
    private val limit: Int,
    private val modelExamples: List<T>,
) : AlwaysRequiresApprovalTool<SUBJECT, List<T>>("This tool modifies the database."){

    context(serverRuntime: ServerRuntime)
    override suspend fun description(auth: AuthAccess<SUBJECT>): TotalExplanation = TotalExplanation(
        unique = """
        Insert records into the ${modelInfo.tableName} table.

        Provide the list of records. The records will be validated and inserted. (Max size $limit)
        """.trimIndent(),
        sharedExplanations = listOf(ModelStructure(serverRuntime, modelInfo))
    )

    override val name: String = "insert_${modelInfo.tableName.lowercase()}"

    override val argsSerializer: KSerializer<List<T>> = ListSerializer(modelInfo.serializer)

    context(serverRuntime: ServerRuntime)
    override suspend fun execute(auth: AuthAccess<SUBJECT>, args: List<T>): String {
        return try {
            if (args.size > limit) return "Error inserting records. Records list is too large. Max $limit records allowed"

            val results = modelInfo.table(auth).insertMany(args)

            if (results.isEmpty())
                "Failed to insert any records"
            else {
                val json = serverRuntime.externalSerialization.json.encodeToString(ListSerializer(modelInfo.serializer), results)
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
    private val limit: Int,
) : AlwaysRequiresApprovalTool<SUBJECT, UpdateTool.Args<T, ID>>("This tool modifies your data."){

    context(serverRuntime: ServerRuntime)
    override suspend fun description(auth: AuthAccess<SUBJECT>): TotalExplanation = TotalExplanation(
        unique = """
        Update records in the ${modelInfo.tableName} table that match a condition.

        The ids parameter is a Json List of data IDs (Max size $limit).

        This would update all records with ids inside the ids parameter to have a published status.
        """.trimIndent(),
        sharedExplanations = listOf(ModelStructure(serverRuntime, modelInfo))
    )

    override val name: String = "update_${modelInfo.tableName.lowercase()}"

    override val argsSerializer: KSerializer<Args<T, ID>> = Args.serializer(modelInfo.serializer, modelInfo.idSerializer)
    context(serverRuntime: ServerRuntime)
    override suspend fun execute(auth: AuthAccess<SUBJECT>, args: Args<T, ID>): String {
        return try {

            if (args.ids.size > limit) return "Error Updating Records. ids list is too large. Max $limit ids allowed"

            val updateResults = modelInfo.table(auth)
                .updateMany(
                    Condition.OnField(modelInfo.serializer._id(), Condition.Inside(args.ids)),
                    args.modification.modification
                )
                .changes
                .mapNotNull { it.new }

            if (updateResults.isEmpty())
                "Failed to update any records"
            else {
                val json = serverRuntime.externalSerialization.json.encodeToString(ListSerializer(modelInfo.serializer), updateResults)
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
    private val limit: Int,
) : AlwaysRequiresApprovalTool<SUBJECT, List<ID>>("This tool permanently deletes some data."){

    override val name: String = "delete_${modelInfo.tableName.lowercase()}"

    context(serverRuntime: ServerRuntime)
    override suspend fun description(auth: AuthAccess<SUBJECT>): TotalExplanation = TotalExplanation(
        unique = """
        Delete records from the ${modelInfo.tableName} table that have the provided ids.

        **WARNING**: This permanently deletes data. Use with caution.
        """.trimIndent(),
        sharedExplanations = listOf(ModelStructure(serverRuntime, modelInfo))
    )

    override val argsSerializer: KSerializer<List<ID>> = ListSerializer(modelInfo.idSerializer)

    context(serverRuntime: ServerRuntime)
    override suspend fun execute(auth: AuthAccess<SUBJECT>, args: List<ID>): String {
        return try {

            if (args.size > limit) return "Error Updating Records. ids list is too large. Max $limit ids allowed"

            val deletedResults =  modelInfo.table(auth)
                .deleteMany(Condition.OnField(modelInfo.serializer._id(), Condition.Inside(args)))
            if (deletedResults.isEmpty())
                "Failed to delete any records"
            else {
                val json = serverRuntime.externalSerialization.json.encodeToString(ListSerializer(modelInfo.serializer), deletedResults)
                "Successfully deleted records from ${modelInfo.tableName}: $json"
            }
        } catch (e: Exception) {
            "Error deleting records: ${e.message}"
        }
    }

}
