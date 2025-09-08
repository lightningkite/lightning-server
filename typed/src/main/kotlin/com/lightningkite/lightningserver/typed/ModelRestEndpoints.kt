package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec.Segment
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.first
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.typed.sdk.SdkModuleInfo
import com.lightningkite.lightningserver.typed.sdk.clientInterface
import com.lightningkite.lightningserver.typed.sdk.defaultInfo
import com.lightningkite.lightningserver.typed.sdk.pascalCase
import com.lightningkite.lightningserver.typed.sdk.sdkSettings
import com.lightningkite.lightningserver.typed.sdk.titleCase
import com.lightningkite.lightningserver.typed.sdk.info
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

public class ModelRestEndpoints<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    public val info: ModelInfo<USER, T, ID>,
) : ServerBuilder() {
    init {
        sdkSettings.clientInterface = ClientModelRestEndpoints::class.info(info.serializer, info.idSerializer)
        sdkSettings.defaultInfo = SdkModuleInfo(
            interfaceName = info.collectionName.pascalCase() + "RestEndpoints",
            valueName = "rest"
        )
    }

    private val detailPath = path.arg(Segment.Wildcard("id", info.idSerializer))
    private val bulkPath = path.path("bulk")

    public val permissions: ApiHttpHandler<PathSpec0, USER, Unit, ModelPermissions<T>> =
        path.path("_permissions_").get bind ApiHttpHandler(
            summary = "Permissions",
            description = "Returns the user's permissions for this collection.",
            inputType = Unit.serializer(),
            outputType = ModelPermissions.serializer(info.serializer),
            auth = info.auth.subscope(ModelInfo.readSubscope),
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { _: Unit ->
                info.permissions(this)
            }
        )


    public val list: ApiHttpHandler<PathSpec0, USER, Query<T>, List<T>> =
        path.get bind ApiHttpHandler(
            summary = "List",
            description = "Gets a list of ${info.collectionName}s.",
            inputType = Query.serializer(info.serializer),
            outputType = ListSerializer(info.serializer),
            auth = info.auth.subscope(ModelInfo.readSubscope),
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { input: Query<T> ->
                info.collection(this)
                    .query(input)
                    .toList()
            }
        )


    public val query: ApiHttpHandler<PathSpec0, USER, Query<T>, List<T>> =
        path.path("query").post bind ApiHttpHandler(
            summary = "Query",
            description = "Gets a list of ${info.collectionName}s that match the given query.",
            inputType = Query.serializer(info.serializer),
            outputType = ListSerializer(info.serializer),
            auth = info.auth.subscope(ModelInfo.readSubscope),
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { input: Query<T> ->
                info.collection(this)
                    .query(input)
                    .toList()
            }
        )


    public val queryPartial: ApiHttpHandler<PathSpec0, USER, QueryPartial<T>, List<Partial<T>>> =
        path.path("query-partial").post bind ApiHttpHandler(
            summary = "QueryPartial",
            description = "Gets parts of ${info.collectionName}s that match the given query.",
            inputType = QueryPartial.serializer(info.serializer),
            outputType = ListSerializer(PartialSerializer(info.serializer)),
            auth = info.auth.subscope(ModelInfo.readSubscope),
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { input: QueryPartial<T> ->
                info.collection(this)
                    .queryPartial(input)
                    .toList()
            }
        )


    public val detail: ApiHttpHandler<PathSpec1<ID>, USER, Unit, T> =
        detailPath.get bind ApiHttpHandler(
            summary = "Detail",
            description = "Gets the ${info.collectionName} for the provided id.",
            inputType = Unit.serializer(),
            outputType = info.serializer,
            auth = info.auth.subscope(ModelInfo.readSubscope),
            belongsToInterface = belongsToInterface,
            errorCases = listOf(
                LSError(
                    http = HttpStatus.NotFound.code,
                    detail = "",
                    message = "There was no known object by that ID.",
                    data = ""
                )
            ),
            examples = emptyList(),
            implementation = { _: Unit ->
                info.collection(this).get(first) ?: throw NotFoundException()
            }
        )


    public val insertBulk: ApiHttpHandler<PathSpec0, USER, List<T>, List<T>> =
        bulkPath.post bind ApiHttpHandler(
            summary = "Insert Bulk",
            description = "Creates multiple ${info.collectionName}s at the same time.",
            inputType = ListSerializer(info.serializer),
            outputType = ListSerializer(info.serializer),
            auth = info.auth.subscope(ModelInfo.createSubscope),
            belongsToInterface = belongsToInterface,
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { values: List<T> ->
                try {
                    info.collection(this)
                        .insert(values)
                } catch (e: UniqueViolationException) {
                    throw BadRequestException(
                        detail = "unique",
                        message = e.key?.titleCase()?.let { "$it already exists" } ?: "Already exists",
                        cause = e)
                }
            }
        )


    public val insert: ApiHttpHandler<PathSpec0, USER, T, T> =
        path.post bind ApiHttpHandler(
            summary = "Insert",
            description = "Creates a new ${info.collectionName}",
            inputType = info.serializer,
            outputType = info.serializer,
            auth = info.auth.subscope(ModelInfo.createSubscope),
            belongsToInterface = belongsToInterface,
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { value: T ->
                try {
                    info.collection(this)
                        .insertOne(value)
                        ?: throw ForbiddenException("Value was not posted as requested.")
                } catch (e: UniqueViolationException) {
                    throw BadRequestException(
                        detail = "unique",
                        message = e.key?.titleCase()?.let { "$it already exists" } ?: "Already exists",
                        cause = e)
                }
            }
        )


    public val upsert: ApiHttpHandler<PathSpec1<ID>, USER, T, T> =
        detailPath.post bind ApiHttpHandler(
            summary = "Upsert",
            description = "Creates or updates a ${info.collectionName}",
            inputType = info.serializer,
            outputType = info.serializer,
            auth = info.auth.subscope(ModelInfo.createSubscope, ModelInfo.updateSubscope),
            belongsToInterface = belongsToInterface,
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { value: T ->
                try {
                    info.collection(this)
                        .upsertOneById(first, value)
                        .new
                        ?: throw NotFoundException()
                } catch (e: UniqueViolationException) {
                    throw BadRequestException(
                        detail = "unique",
                        message = e.key?.titleCase()?.let { "$it already exists" } ?: "Already exists",
                        cause = e
                    )
                }
            }
        )


    public val bulkReplace: ApiHttpHandler<PathSpec0, USER, List<T>, List<T>> =
        bulkPath.put bind ApiHttpHandler(
            summary = "Bulk Replace",
            description = "Modifies many ${info.collectionName}s at the same time by ID.",
            inputType = ListSerializer(info.serializer),
            outputType = ListSerializer(info.serializer),
            auth = info.auth.subscope(ModelInfo.updateSubscope),
            belongsToInterface = belongsToInterface,
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { values: List<T> ->
                try {
                    val db = info.collection(this)
                    values.map { db.replaceOneById(it._id, it) }.mapNotNull { it.new }
                } catch (e: UniqueViolationException) {
                    throw BadRequestException(
                        detail = "unique",
                        message = e.key?.titleCase()?.let { "$it already exists" } ?: "Already exists",
                        cause = e
                    )
                }
            }
        )


    public val replace: ApiHttpHandler<PathSpec1<ID>, USER, T, T> =
        detailPath.put bind ApiHttpHandler(
            summary = "Replace",
            description = "Replaces a single ${info.collectionName} by ID.",
            inputType = info.serializer,
            outputType = info.serializer,
            auth = info.auth.subscope(ModelInfo.updateSubscope),
            belongsToInterface = belongsToInterface,
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { value: T ->
                try {
                    info.collection(this)
                        .replaceOneById(first, value)
                        .new
                        ?: throw NotFoundException()
                } catch (e: UniqueViolationException) {
                    throw BadRequestException(
                        detail = "unique",
                        message = e.key?.titleCase()?.let { "$it already exists" } ?: "Already exists",
                        cause = e
                    )
                }
            }
        )


    public val bulkModify: ApiHttpHandler<PathSpec0, USER, MassModification<T>, Int> =
        bulkPath.patch bind ApiHttpHandler(
            summary = "Bulk Modify",
            description = "Modifies many ${info.collectionName}s at the same time. Returns the number of changed items.",
            inputType = MassModification.serializer(info.serializer),
            outputType = Int.serializer(),
            auth = info.auth.subscope(ModelInfo.updateSubscope),
            belongsToInterface = belongsToInterface,
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { input: MassModification<T> ->
                try {
                    info.collection(this)
                        .updateManyIgnoringResult(input)
                } catch (e: UniqueViolationException) {
                    throw BadRequestException(
                        detail = "unique",
                        message = e.key?.titleCase()?.let { "$it already exists" } ?: "Already exists",
                        cause = e
                    )
                }
            }
        )


    public val modifyWithDiff: ApiHttpHandler<PathSpec1<ID>, USER, Modification<T>, EntryChange<T>> =
        detailPath.path("delta").patch bind ApiHttpHandler(
            summary = "Modify with Diff",
            description = "Modifies a ${info.collectionName} by ID, returning both the previous value and new value.",
            inputType = Modification.serializer(info.serializer),
            outputType = EntryChange.serializer(info.serializer),
            auth = info.auth.subscope(ModelInfo.updateSubscope),
            belongsToInterface = belongsToInterface,
            errorCases = listOf(
                LSError(
                    http = HttpStatus.NotFound.code,
                    detail = "",
                    message = "There was no known object by that ID.",
                    data = ""
                )
            ),
            examples = emptyList(),
            implementation = { input: Modification<T> ->
                try {
                    info.collection(this)
                        .updateOneById(first, input)
                        .also { if (it.old == null && it.new == null) throw NotFoundException() }
                } catch (e: UniqueViolationException) {
                    throw BadRequestException(
                        detail = "unique",
                        message = e.key?.titleCase()?.let { "$it already exists" } ?: "Already exists",
                        cause = e
                    )
                }
            }
        )


    public val modify: ApiHttpHandler<PathSpec1<ID>, USER, Modification<T>, T> =
        detailPath.patch bind ApiHttpHandler(
            summary = "Modify",
            description = "Modifies a ${info.collectionName} by ID, returning the new value.",
            inputType = Modification.serializer(info.serializer),
            outputType = info.serializer,
            auth = info.auth.subscope(ModelInfo.updateSubscope),
            belongsToInterface = belongsToInterface,
            errorCases = listOf(
                LSError(
                    http = HttpStatus.NotFound.code,
                    detail = "",
                    message = "There was no known object by that ID.",
                    data = ""
                )
            ),
            examples = emptyList(),
            implementation = { input: Modification<T> ->
                try {
                    info.collection(this)
                        .updateOneById(first, input)
                        .also { if (it.old == null && it.new == null) throw NotFoundException() }
                        .new!!
                } catch (e: UniqueViolationException) {
                    throw BadRequestException(
                        detail = "unique",
                        message = e.key?.titleCase()?.let { "$it already exists" } ?: "Already exists",
                        cause = e
                    )
                }
            }
        )


    public val modifySimple: ApiHttpHandler<PathSpec1<ID>, USER, Partial<T>, T> =
        detailPath.path("simplified").patch bind ApiHttpHandler(
            summary = "Simplified Modify",
            description = "Modifies a ${info.collectionName} by ID, returning the new value.",
            inputType = PartialSerializer(info.serializer),
            outputType = info.serializer,
            auth = info.auth.subscope(ModelInfo.updateSubscope),
            belongsToInterface = belongsToInterface,
            errorCases = listOf(
                LSError(
                    http = HttpStatus.NotFound.code,
                    detail = "",
                    message = "There was no known object by that ID.",
                    data = ""
                )
            ),
            examples = emptyList(),
            implementation = { input: Partial<T> ->
                try {
                    info.collection(this)
                        .updateOneById(first, input.toModification(info.serializer))
                        .also { if (it.old == null && it.new == null) throw NotFoundException() }
                        .new!!
                } catch (e: UniqueViolationException) {
                    throw BadRequestException(
                        detail = "unique",
                        message = e.key?.titleCase()?.let { "$it already exists" } ?: "Already exists",
                        cause = e
                    )
                }
            }
        )


    public val bulkDelete: ApiHttpHandler<PathSpec0, USER, Condition<T>, Int> =
        path.path("bulk-delete").post bind ApiHttpHandler(
            summary = "Bulk Delete",
            description = "Deletes all matching ${info.collectionName}s, returning the number of deleted items.",
            inputType = Condition.serializer(info.serializer),
            outputType = Int.serializer(),
            auth = info.auth.subscope(ModelInfo.deleteSubscope),
            belongsToInterface = belongsToInterface,
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { filter: Condition<T> ->
                info.collection(this).deleteManyIgnoringOld(filter)
            }
        )


    public val deleteItem: ApiHttpHandler<PathSpec1<ID>, USER, Unit, Unit> =
        detailPath.delete bind ApiHttpHandler(
            summary = "Delete",
            description = "Deletes a ${info.collectionName} by id.",
            inputType = Unit.serializer(),
            outputType = Unit.serializer(),
            auth = info.auth.subscope(ModelInfo.deleteSubscope),
            belongsToInterface = belongsToInterface,
            errorCases = listOf(
                LSError(
                    http = HttpStatus.NotFound.code,
                    detail = "",
                    message = "There was no known object by that ID.",
                    data = ""
                )
            ),
            examples = emptyList(),
            implementation = { _: Unit ->
                if (!info.collection(this).deleteOneById(first)) {
                    throw NotFoundException()
                }
                Unit
            }
        )

    public val count: ApiHttpHandler<PathSpec0, USER, Condition<T>, Int> =
        path.path("count").post bind ApiHttpHandler(
            summary = "Count",
            description = "Gets the total number of ${info.collectionName}s matching the given condition.",
            inputType = Condition.serializer(info.serializer),
            outputType = Int.serializer(),
            auth = info.auth.subscope(ModelInfo.readSubscope),
            belongsToInterface = belongsToInterface,
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { condition: Condition<T> ->
                info.collection(this).count(condition)
            }
        )


    public val groupCount: ApiHttpHandler<PathSpec0, USER, GroupCountQuery<T>, Map<String, Int>> =
        path.path("group-count").post bind ApiHttpHandler(
            summary = "Group Count",
            description = "Gets the total number of ${info.collectionName}s matching the given condition divided by group.",
            inputType = GroupCountQuery.serializer(info.serializer),
            outputType = MapSerializer(String.serializer(), Int.serializer()),
            auth = info.auth.subscope(ModelInfo.readSubscope),
            belongsToInterface = belongsToInterface,
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { condition: GroupCountQuery<T> ->
                @Suppress("UNCHECKED_CAST")
                info.collection(this)
                    .groupCount(condition.condition, condition.groupBy as DataClassPath<T, Any?>)
                    .mapKeys { it.key.toString() }
            }
        )


    public val aggregate: ApiHttpHandler<PathSpec0, USER, AggregateQuery<T>, Double?> =
        path.path("aggregate").post bind ApiHttpHandler(
            summary = "Aggregate",
            description = "Aggregates a property of ${info.collectionName}s matching the given condition.",
            inputType = AggregateQuery.serializer(info.serializer),
            outputType = Double.serializer().nullable,
            auth = info.auth.subscope(ModelInfo.readSubscope),
            belongsToInterface = belongsToInterface,
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { condition: AggregateQuery<T> ->
                @Suppress("UNCHECKED_CAST")
                info.collection(this)
                    .aggregate(
                        condition.aggregate,
                        condition.condition,
                        condition.property as DataClassPath<T, Number>
                    )
            }
        )


    public val groupAggregate: ApiHttpHandler<PathSpec0, USER, GroupAggregateQuery<T>, Map<String, Double?>> =
        path.path("group-aggregate").post bind ApiHttpHandler(
            summary = "Group Aggregate",
            description = "Aggregates a property of ${info.collectionName}s matching the given condition divided by group.",
            inputType = GroupAggregateQuery.serializer(info.serializer),
            outputType = MapSerializer(String.serializer(), Double.serializer().nullable),
            auth = info.auth.subscope(ModelInfo.readSubscope),
            belongsToInterface = belongsToInterface,
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { condition: GroupAggregateQuery<T> ->
                @Suppress("UNCHECKED_CAST")
                info.collection(this)
                    .groupAggregate(
                        condition.aggregate,
                        condition.condition,
                        condition.groupBy as DataClassPath<T, Any?>,
                        condition.property as DataClassPath<T, Number>
                    )
                    .mapKeys { it.key.toString() }
            }
        )


    public val groupCount2: ApiHttpHandler<PathSpec0, USER, GroupCountQuery<T>, Map<String, Int>> =
        path.path("group-count-2").post bind ApiHttpHandler(
            summary = "Group Count 2",
            description = "Gets the total number of ${info.collectionName}s matching the given condition divided by group.",
            inputType = GroupCountQuery.serializer(info.serializer),
            outputType = MapSerializer(String.serializer(), Int.serializer()),
            auth = info.auth.subscope(ModelInfo.readSubscope),
            belongsToInterface = belongsToInterface,
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { condition: GroupCountQuery<T> ->
                @Suppress("UNCHECKED_CAST")
                val keySerializer = condition.groupBy.serializerAny as KSerializer<Any?>
                @Suppress("UNCHECKED_CAST")
                info.collection(this)
                    .groupCount(condition.condition, condition.groupBy as DataClassPath<T, Any?>)
                    .mapKeys { serverRuntime.externalSerialization.json.encodeToString(keySerializer, it.key) }
            }
        )


    public val groupAggregate2: ApiHttpHandler<PathSpec0, USER, GroupAggregateQuery<T>, Map<String, Double?>> =
        path.path("group-aggregate-2").post bind ApiHttpHandler(
            summary = "Group Aggregate 2",
            description = "Aggregates a property of ${info.collectionName}s matching the given condition divided by group.",
            inputType = GroupAggregateQuery.serializer(info.serializer),
            outputType = MapSerializer(String.serializer(), Double.serializer().nullable),
            auth = info.auth.subscope(ModelInfo.readSubscope),
            belongsToInterface = belongsToInterface,
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { condition: GroupAggregateQuery<T> ->
                @Suppress("UNCHECKED_CAST")
                val keySerializer = condition.groupBy.serializerAny as KSerializer<Any?>
                @Suppress("UNCHECKED_CAST")
                info.collection(this)
                    .groupAggregate(
                        condition.aggregate,
                        condition.condition,
                        condition.groupBy as DataClassPath<T, Any?>,
                        condition.property as DataClassPath<T, Number>
                    )
                    .mapKeys { serverRuntime.externalSerialization.json.encodeToString(keySerializer, it.key) }
            }
        )
}