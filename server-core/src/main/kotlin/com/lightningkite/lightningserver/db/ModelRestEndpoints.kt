package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.exceptions.ForbiddenException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.typed.ApiEndpoint
import com.lightningkite.lightningserver.typed.typed
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlin.reflect.KProperty1

open class ModelRestEndpoints<USER, T : HasId<ID>, ID : Comparable<ID>>(
    path: ServerPath,
    val info: ModelInfo<USER, T, ID>
) : ServerPathGroup(path) {

    val modelName = info.serialization.serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.')

    init {
        path.docName = modelName
    }

    val list = get.typed(
        authInfo = info.serialization.authInfo,
        inputType = Query.serializer(info.serialization.serializer),
        outputType = ListSerializer(info.serialization.serializer),
        summary = "List",
        description = "Gets a list of ${modelName}s.",
        errorCases = listOf(),
        implementation = { user: USER, input: Query<T> ->
            info.collection(user)
                .query(input)
                .toList()
        }
    )

    // This is used to GET a list objects, but rather than the query being in the parameter
    // it's in the POST body.
    val query = post("query").typed(
        authInfo = info.serialization.authInfo,
        inputType = Query.serializer(info.serialization.serializer),
        outputType = ListSerializer(info.serialization.serializer),
        summary = "Query",
        description = "Gets a list of ${modelName}s that match the given query.",
        errorCases = listOf(),
        implementation = { user: USER, input: Query<T> ->
            info.collection(user)
                .query(input)
                .toList()
        }
    )

    // This is used get a single object with id of _id
    val detail = get("{id}").typed(
        authInfo = info.serialization.authInfo,
        inputType = Unit.serializer(),
        outputType = info.serialization.serializer,
        pathType = info.serialization.idSerializer,
        summary = "Detail",
        description = "Gets a single ${modelName} by ID.",
        errorCases = listOf(
            ApiEndpoint.ErrorCase(
                status = HttpStatus.NotFound,
                internalCode = 0,
                description = "There was no known object by that ID."
            ),
            ApiEndpoint.ErrorCase(
                status = HttpStatus.BadRequest,
                internalCode = 0,
                description = "The ID could not be parsed."
            )
        ),
        implementation = { user: USER, id: ID, input: Unit ->
            info.collection(user)
                .get(id)
                ?: throw NotFoundException()
        }
    )

    val insertBulk = post("bulk").typed(
        authInfo = info.serialization.authInfo,
        inputType = ListSerializer(info.serialization.serializer),
        outputType = ListSerializer(info.serialization.serializer),
        summary = "Insert Bulk",
        description = "Creates multiple ${modelName}s at the same time.",
        errorCases = listOf(),
        successCode = HttpStatus.Created,
        implementation = { user: USER, values: List<T> ->
            info.collection(user)
                .insertMany(values)
        }
    )

    val insert = post("").typed(
        authInfo = info.serialization.authInfo,
        inputType = info.serialization.serializer,
        outputType = info.serialization.serializer,
        summary = "Insert",
        description = "Creates a new ${modelName}",
        errorCases = listOf(),
        successCode = HttpStatus.Created,
        implementation = { user: USER, value: T ->
            info.collection(user)
                .insertOne(value)
                ?: throw ForbiddenException("Value was not posted as requested.")
        }
    )

    val upsert = post("{id}").typed(
        authInfo = info.serialization.authInfo,
        inputType = info.serialization.serializer,
        outputType = info.serialization.serializer,
        pathType = info.serialization.idSerializer,
        summary = "Upsert",
        description = "Creates or updates a ${modelName}",
        errorCases = listOf(),
        successCode = HttpStatus.Created,
        implementation = { user: USER, id: ID, value: T ->
            info.collection(user)
                .upsertOneById(id, value)
                .new
                ?: throw NotFoundException()
        }
    )

    // This is used replace many objects at once. This does make individual calls to the database. Kmongo does not have a many replace option.
    val bulkReplace = put("").typed(
        authInfo = info.serialization.authInfo,
        inputType = ListSerializer(info.serialization.serializer),
        outputType = ListSerializer(info.serialization.serializer),
        summary = "Bulk Replace",
        description = "Modifies many ${modelName}s at the same time by ID.",
        errorCases = listOf(),
        implementation = { user: USER, values: List<T> ->
            val db = info.collection(user)
            values.map { db.replaceOneById(it._id, it) }.mapNotNull { it.new }
        }
    )

    val replace = put("{id}").typed(
        authInfo = info.serialization.authInfo,
        inputType = info.serialization.serializer,
        outputType = info.serialization.serializer,
        pathType = info.serialization.idSerializer,
        summary = "Replace",
        description = "Replaces a single ${modelName} by ID.",
        errorCases = listOf(
            ApiEndpoint.ErrorCase(
                status = HttpStatus.NotFound,
                internalCode = 0,
                description = "There was no known object by that ID."
            ),
            ApiEndpoint.ErrorCase(
                status = HttpStatus.BadRequest,
                internalCode = 0,
                description = "The ID could not be parsed."
            )
        ),
        implementation = { user: USER, id: ID, value: T ->
            info.collection(user)
                .replaceOneById(id, value)
                .new
                ?: throw NotFoundException()
        }
    )

    val bulkModify = patch("bulk").typed(
        authInfo = info.serialization.authInfo,
        inputType = MassModification.serializer(info.serialization.serializer),
        outputType = Int.serializer(),
        summary = "Bulk Modify",
        description = "Modifies many ${modelName}s at the same time.  Returns the number of changed items.",
        errorCases = listOf(),
        implementation = { user: USER, input: MassModification<T> ->
            info.collection(user)
                .updateManyIgnoringResult(input)
        }
    )

    val modifyWithDiff = patch("{id}/delta").typed(
        authInfo = info.serialization.authInfo,
        inputType = Modification.serializer(info.serialization.serializer),
        outputType = EntryChange.serializer(info.serialization.serializer),
        pathType = info.serialization.idSerializer,
        summary = "Modify with Diff",
        description = "Modifies a ${modelName} by ID, returning both the previous value and new value.",
        errorCases = listOf(
            ApiEndpoint.ErrorCase(
                status = HttpStatus.NotFound,
                internalCode = 0,
                description = "There was no known object by that ID."
            ),
            ApiEndpoint.ErrorCase(
                status = HttpStatus.BadRequest,
                internalCode = 0,
                description = "The ID could not be parsed."
            )
        ),
        implementation = { user: USER, id: ID, input: Modification<T> ->
            info.collection(user)
                .updateOneById(id, input)
                .also { if (it.old == null && it.new == null) throw NotFoundException() }
        }
    )

    val modify = patch("{id}").typed(
        authInfo = info.serialization.authInfo,
        inputType = Modification.serializer(info.serialization.serializer),
        outputType = info.serialization.serializer,
        pathType = info.serialization.idSerializer,
        summary = "Modify",
        description = "Modifies a ${modelName} by ID, returning both the previous value and new value.",
        errorCases = listOf(
            ApiEndpoint.ErrorCase(
                status = HttpStatus.NotFound,
                internalCode = 0,
                description = "There was no known object by that ID."
            ),
            ApiEndpoint.ErrorCase(
                status = HttpStatus.BadRequest,
                internalCode = 0,
                description = "The ID could not be parsed."
            )
        ),
        implementation = { user: USER, id: ID, input: Modification<T> ->
            info.collection(user)
                .updateOneById(id, input)
                .also { if (it.old == null && it.new == null) throw NotFoundException() }
                .new!!
        }
    )

    val bulkDelete = post("bulk-delete").typed(
        authInfo = info.serialization.authInfo,
        inputType = Condition.serializer(info.serialization.serializer),
        outputType = Int.serializer(),
        summary = "Bulk Delete",
        description = "Deletes all matching ${modelName}s, returning the number of deleted items.",
        errorCases = listOf(),
        implementation = { user: USER, filter: Condition<T> ->
            info.collection(user)
                .deleteManyIgnoringOld(filter)
        }
    )

    val deleteItem = delete("{id}").typed(
        authInfo = info.serialization.authInfo,
        inputType = Unit.serializer(),
        outputType = Unit.serializer(),
        pathType = info.serialization.idSerializer,
        summary = "Delete",
        description = "Deletes a ${modelName} by id.",
        errorCases = listOf(
            ApiEndpoint.ErrorCase(
                status = HttpStatus.NotFound,
                internalCode = 0,
                description = "There was no known object by that ID."
            ),
            ApiEndpoint.ErrorCase(
                status = HttpStatus.BadRequest,
                internalCode = 0,
                description = "The ID could not be parsed."
            )
        ),
        implementation = { user: USER, id: ID, _: Unit ->
            if (!info.collection(user)
                    .deleteOneById(id)
            ) {
                throw NotFoundException()
            }
            Unit
        }
    )

    val countGet = get("count").typed(
        authInfo = info.serialization.authInfo,
        inputType = Condition.serializer(info.serialization.serializer),
        outputType = Int.serializer(),
        summary = "Count",
        description = "Gets the total number of ${modelName}s matching the given condition.",
        errorCases = listOf(),
        implementation = { user: USER, condition: Condition<T> ->
            info.collection(user)
                .count(condition)
        }
    )

    val count = post("count").typed(
        authInfo = info.serialization.authInfo,
        inputType = Condition.serializer(info.serialization.serializer),
        outputType = Int.serializer(),
        summary = "Count",
        description = "Gets the total number of ${modelName}s matching the given condition.",
        errorCases = listOf(),
        implementation =
        { user: USER, condition: Condition<T> ->
            info.collection(user)
                .count(condition)
        }
    )

    val groupCount = post("group-count").typed(
        authInfo = info.serialization.authInfo,
        inputType = GroupCountQuery.serializer(info.serialization.serializer),
        outputType = MapSerializer(String.serializer(), Int.serializer()),
        summary = "Group Count",
        description = "Gets the total number of ${modelName}s matching the given condition divided by group.",
        errorCases = listOf(),
        implementation = { user: USER, condition: GroupCountQuery<T> ->
            @Suppress("UNCHECKED_CAST")
            info.collection(user)
                .groupCount(condition.condition, condition.groupBy.property as KProperty1<T, Any?>)
                .mapKeys { it.key.toString() }
        }
    )

    val aggregate = post("aggregate").typed(
        authInfo = info.serialization.authInfo,
        inputType = AggregateQuery.serializer(info.serialization.serializer),
        outputType = Double.serializer().nullable,
        summary = "Aggregate",
        description = "Aggregates a property of ${modelName}s matching the given condition.",
        errorCases = listOf(),
        implementation = { user: USER, condition: AggregateQuery<T> ->
            @Suppress("UNCHECKED_CAST")
            info.collection(user)
                .aggregate(
                    condition.aggregate,
                    condition.condition,
                    condition.property.property as KProperty1<T, Number>
                )
        }
    )

    val groupAggregate = post("group-aggregate").typed(
        authInfo = info.serialization.authInfo,
        inputType = GroupAggregateQuery.serializer(info.serialization.serializer),
        outputType = MapSerializer(String.serializer(), Double.serializer().nullable),
        summary = "Group Aggregate",
        description = "Aggregates a property of ${modelName}s matching the given condition divided by group.",
        errorCases = listOf(),
        implementation = { user: USER, condition: GroupAggregateQuery<T> ->
            @Suppress("UNCHECKED_CAST")
            info.collection(user)
                .groupAggregate(
                    condition.aggregate,
                    condition.condition,
                    condition.groupBy.property as KProperty1<T, Any?>,
                    condition.property.property as KProperty1<T, Number>
                )
                .mapKeys { it.key.toString() }
        }
    )
}