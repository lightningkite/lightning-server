package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.subscope
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.pathing.PathSpec.Segment
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.*

public class ModelRestEndpoints<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    public val info: ModelInfo<USER, T, ID>,
) : ServerBuilder() {
    init {
        sdkSettings.clientInterface = ClientModelRestEndpoints::class.info(info.serializer, info.idSerializer)
        sdkSettings.defaultInfo = SdkModule.Info(
            interfaceName = info.tableName.pascalCase() + "RestEndpoints",
            valueName = "rest"
        )
    }

    public val detailPath: PathSpec1<ID> = path.arg(Segment.Wildcard("id", info.idSerializer))
    private val bulkPath = path.path("bulk")

    // Errors actually thrown by the implementations below; declared so docs/SDKs advertise them and the
    // W6 "undeclared error" advisory stays quiet.
    private val notFoundError = LSError(
        http = HttpStatus.NotFound.code,
        detail = "",
        message = "There was no known object by that ID.",
    )
    private val uniqueViolationError = LSError(
        http = HttpStatus.BadRequest.code,
        detail = "unique",
        message = "A unique constraint was violated.",
    )

    public val permissions: ApiHttpHandler<PathSpec0, USER, Unit, ModelPermissions<T>> =
        path.path("_permissions_").get bind explicitApiHttpHandler(
            summary = "Permissions",
            description = "Returns the user's permissions for this collection.",
            inputType = Unit.serializer(),
            outputType = ModelPermissions.serializer(info.serializer),
            auth = info.auth.subscope(ModelInfo.Scopes.read),
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { _: Unit ->
                info.permissions(this)
            }
        )


    public val list: ApiHttpHandler<PathSpec0, USER, Query<T>, List<T>> =
        path.get bind explicitApiHttpHandler(
            summary = "List",
            description = "Gets a list of ${info.tableName}s.",
            inputType = Query.serializer(info.serializer),
            outputType = ListSerializer(info.serializer),
            auth = info.auth.subscope(ModelInfo.Scopes.read),
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { input: Query<T> ->
                info.table(this)
                    .query(input)
                    .toList()
            }
        )


    public val query: ApiHttpHandler<PathSpec0, USER, Query<T>, List<T>> =
        path.path("query").post bind explicitApiHttpHandler(
            summary = "Query",
            description = "Gets a list of ${info.tableName}s that match the given query.",
            inputType = Query.serializer(info.serializer),
            outputType = ListSerializer(info.serializer),
            auth = info.auth.subscope(ModelInfo.Scopes.read),
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { input: Query<T> ->
                info.table(this)
                    .query(input)
                    .toList()
            }
        )


    public val queryPartial: ApiHttpHandler<PathSpec0, USER, QueryPartial<T>, List<Partial<T>>> =
        path.path("query-partial").post bind explicitApiHttpHandler(
            summary = "QueryPartial",
            description = "Gets parts of ${info.tableName}s that match the given query.",
            inputType = QueryPartial.serializer(info.serializer),
            outputType = ListSerializer(PartialSerializer(info.serializer)),
            auth = info.auth.subscope(ModelInfo.Scopes.read),
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { input: QueryPartial<T> ->
                info.table(this)
                    .queryPartial(input)
                    .toList()
            }
        )


    public val detail: ApiHttpHandler<PathSpec1<ID>, USER, Unit, T> =
        detailPath.get bind explicitApiHttpHandler(
            summary = "Detail",
            description = "Gets the ${info.tableName} for the provided id.",
            inputType = Unit.serializer(),
            outputType = info.serializer,
            auth = info.auth.subscope(ModelInfo.Scopes.read),
            errorCases = listOf(notFoundError),
            examples = emptyList(),
            implementation = { _: Unit ->
                info.table(this).get(route.arg1) ?: throw NotFoundException()
            }
        )


    public val insertBulk: ApiHttpHandler<PathSpec0, USER, List<T>, List<T>> =
        bulkPath.post bind explicitApiHttpHandler(
            summary = "Insert Bulk",
            description = "Creates multiple ${info.tableName}s at the same time.",
            inputType = ListSerializer(info.serializer),
            outputType = ListSerializer(info.serializer),
            auth = info.auth.subscope(ModelInfo.Scopes.create),
            errorCases = listOf(uniqueViolationError),
            examples = emptyList(),
            implementation = { values: List<T> ->
                try {
                    info.table(this)
                        .insert(values)
                } catch (e: UniqueViolationException) {
                    throw BadRequestException(
                        detail = "unique",
                        message = e.key?.titleCase()?.let { "$it already exists" } ?: "Already exists",
                        cause = e
                    )
                }
            }
        )


    public val insert: ApiHttpHandler<PathSpec0, USER, T, T> =
        path.post bind explicitApiHttpHandler(
            summary = "Insert",
            description = "Creates a new ${info.tableName}",
            inputType = info.serializer,
            outputType = info.serializer,
            auth = info.auth.subscope(ModelInfo.Scopes.create),
            errorCases = listOf(uniqueViolationError),
            examples = emptyList(),
            implementation = { value: T ->
                try {
                    info.table(this)
                        .insert(listOf(value)).firstOrNull()
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
        detailPath.post bind explicitApiHttpHandler(
            summary = "Upsert",
            description = "Creates or updates a ${info.tableName}",
            inputType = info.serializer,
            outputType = info.serializer,
            auth = info.auth.subscope(listOf(ModelInfo.Scopes.create, ModelInfo.Scopes.update)),
            errorCases = listOf(notFoundError, uniqueViolationError),
            examples = emptyList(),
            implementation = { value: T ->
                try {
                    info.table(this)
                        .upsertOneById(route.arg1, value)
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
        bulkPath.put bind explicitApiHttpHandler(
            summary = "Bulk Replace",
            description = "Modifies many ${info.tableName}s at the same time by ID.",
            inputType = ListSerializer(info.serializer),
            outputType = ListSerializer(info.serializer),
            auth = info.auth.subscope(ModelInfo.Scopes.update),
            errorCases = listOf(uniqueViolationError),
            examples = emptyList(),
            implementation = { values: List<T> ->
                try {
                    val db = info.table(this)
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
        detailPath.put bind explicitApiHttpHandler(
            summary = "Replace",
            description = "Replaces a single ${info.tableName} by ID.",
            inputType = info.serializer,
            outputType = info.serializer,
            auth = info.auth.subscope(ModelInfo.Scopes.update),
            errorCases = listOf(notFoundError, uniqueViolationError),
            examples = emptyList(),
            implementation = { value: T ->
                try {
                    info.table(this)
                        .replaceOneById(route.arg1, value)
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
        bulkPath.patch bind explicitApiHttpHandler(
            summary = "Bulk Modify",
            description = "Modifies many ${info.tableName}s at the same time. Returns the number of changed items.",
            inputType = MassModification.serializer(info.serializer),
            outputType = Int.serializer(),
            auth = info.auth.subscope(ModelInfo.Scopes.update),
            errorCases = listOf(uniqueViolationError),
            examples = emptyList(),
            implementation = { input: MassModification<T> ->
                try {
                    info.table(this)
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
        detailPath.path("delta").patch bind explicitApiHttpHandler(
            summary = "Modify with Diff",
            description = "Modifies a ${info.tableName} by ID, returning both the previous value and new value.",
            inputType = Modification.serializer(info.serializer),
            outputType = EntryChange.serializer(info.serializer),
            auth = info.auth.subscope(ModelInfo.Scopes.update),
            errorCases = listOf(notFoundError, uniqueViolationError),
            examples = emptyList(),
            implementation = { input: Modification<T> ->
                try {
                    info.table(this)
                        .updateOneById(route.arg1, input)
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
        detailPath.patch bind explicitApiHttpHandler(
            summary = "Modify",
            description = "Modifies a ${info.tableName} by ID, returning the new value.",
            inputType = Modification.serializer(info.serializer),
            outputType = info.serializer,
            auth = info.auth.subscope(ModelInfo.Scopes.update),
            errorCases = listOf(notFoundError, uniqueViolationError),
            examples = emptyList(),
            implementation = { input: Modification<T> ->
                try {
                    info.table(this)
                        .updateOneById(route.arg1, input)
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
        detailPath.path("simplified").patch bind explicitApiHttpHandler(
            summary = "Simplified Modify",
            description = "Modifies a ${info.tableName} by ID, returning the new value.",
            inputType = PartialSerializer(info.serializer),
            outputType = info.serializer,
            auth = info.auth.subscope(ModelInfo.Scopes.update),
            errorCases = listOf(notFoundError, uniqueViolationError),
            examples = emptyList(),
            implementation = { input: Partial<T> ->
                try {
                    info.table(this)
                        .updateOneById(route.arg1, input.toModification(info.serializer))
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
        path.path("bulk-delete").post bind explicitApiHttpHandler(
            summary = "Bulk Delete",
            description = "Deletes all matching ${info.tableName}s, returning the number of deleted items.",
            inputType = Condition.serializer(info.serializer),
            outputType = Int.serializer(),
            auth = info.auth.subscope(ModelInfo.Scopes.delete),
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { filter: Condition<T> ->
                info.table(this).deleteManyIgnoringOld(filter)
            }
        )


    public val deleteItem: ApiHttpHandler<PathSpec1<ID>, USER, Unit, Unit> =
        detailPath.delete bind explicitApiHttpHandler(
            summary = "Delete",
            description = "Deletes a ${info.tableName} by id.",
            inputType = Unit.serializer(),
            outputType = Unit.serializer(),
            auth = info.auth.subscope(ModelInfo.Scopes.delete),
            errorCases = listOf(notFoundError),
            examples = emptyList(),
            implementation = { _: Unit ->
                if (!info.table(this).deleteOneById(route.arg1)) {
                    throw NotFoundException()
                }
                Unit
            }
        )

    public val count: ApiHttpHandler<PathSpec0, USER, Condition<T>, Int> =
        path.path("count").post bind explicitApiHttpHandler(
            summary = "Count",
            description = "Gets the total number of ${info.tableName}s matching the given condition.",
            inputType = Condition.serializer(info.serializer),
            outputType = Int.serializer(),
            auth = info.auth.subscope(ModelInfo.Scopes.read),
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { condition: Condition<T> ->
                info.table(this).count(condition)
            }
        )


    public val groupCount: ApiHttpHandler<PathSpec0, USER, GroupCountQuery<T>, Map<String, Int>> =
        path.path("group-count").post bind explicitApiHttpHandler(
            summary = "Group Count",
            description = "Gets the total number of ${info.tableName}s matching the given condition divided by group.",
            inputType = GroupCountQuery.serializer(info.serializer),
            outputType = MapSerializer(String.serializer(), Int.serializer()),
            auth = info.auth.subscope(ModelInfo.Scopes.read),
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { condition: GroupCountQuery<T> ->
                @Suppress("UNCHECKED_CAST")
                info.table(this)
                    .groupCount(condition.condition, condition.groupBy as DataClassPath<T, Any?>)
                    .mapKeys { it.key.toString() }
            }
        )


    public val aggregate: ApiHttpHandler<PathSpec0, USER, AggregateQuery<T>, Double?> =
        path.path("aggregate").post bind explicitApiHttpHandler(
            summary = "Aggregate",
            description = "Aggregates a property of ${info.tableName}s matching the given condition.",
            inputType = AggregateQuery.serializer(info.serializer),
            outputType = Double.serializer().nullable,
            auth = info.auth.subscope(ModelInfo.Scopes.read),
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { condition: AggregateQuery<T> ->
                @Suppress("UNCHECKED_CAST")
                info.table(this)
                    .aggregate(
                        condition.aggregate,
                        condition.condition,
                        condition.property as DataClassPath<T, Number>
                    )
            }
        )


    public val groupAggregate: ApiHttpHandler<PathSpec0, USER, GroupAggregateQuery<T>, Map<String, Double?>> =
        path.path("group-aggregate").post bind explicitApiHttpHandler(
            summary = "Group Aggregate",
            description = "Aggregates a property of ${info.tableName}s matching the given condition divided by group.",
            inputType = GroupAggregateQuery.serializer(info.serializer),
            outputType = MapSerializer(String.serializer(), Double.serializer().nullable),
            auth = info.auth.subscope(ModelInfo.Scopes.read),
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { condition: GroupAggregateQuery<T> ->
                @Suppress("UNCHECKED_CAST")
                info.table(this)
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
        path.path("group-count-2").post bind explicitApiHttpHandler(
            summary = "Group Count 2",
            description = "Gets the total number of ${info.tableName}s matching the given condition divided by group.",
            inputType = GroupCountQuery.serializer(info.serializer),
            outputType = MapSerializer(String.serializer(), Int.serializer()),
            auth = info.auth.subscope(ModelInfo.Scopes.read),
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { condition: GroupCountQuery<T> ->
                @Suppress("UNCHECKED_CAST")
                val keySerializer = condition.groupBy.serializerAny as KSerializer<Any?>
                @Suppress("UNCHECKED_CAST")
                info.table(this)
                    .groupCount(condition.condition, condition.groupBy as DataClassPath<T, Any?>)
                    .mapKeys { serverRuntime.externalSerialization.json.encodeToString(keySerializer, it.key) }
            }
        )


    public val groupAggregate2: ApiHttpHandler<PathSpec0, USER, GroupAggregateQuery<T>, Map<String, Double?>> =
        path.path("group-aggregate-2").post bind explicitApiHttpHandler(
            summary = "Group Aggregate 2",
            description = "Aggregates a property of ${info.tableName}s matching the given condition divided by group.",
            inputType = GroupAggregateQuery.serializer(info.serializer),
            outputType = MapSerializer(String.serializer(), Double.serializer().nullable),
            auth = info.auth.subscope(ModelInfo.Scopes.read),
            errorCases = emptyList(),
            examples = emptyList(),
            implementation = { condition: GroupAggregateQuery<T> ->
                @Suppress("UNCHECKED_CAST")
                val keySerializer = condition.groupBy.serializerAny as KSerializer<Any?>
                @Suppress("UNCHECKED_CAST")
                info.table(this)
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