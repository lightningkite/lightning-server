package com.lightningkite.lightningserver.db

import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.typed.*
import io.ktor.http.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.*
import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.AuthInfo
import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.serializerOrContextual
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.util.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer
import java.util.*
import kotlin.reflect.KProperty1


/**
 * Creates a Restful API for the model provided.
 * The functionalities include:
 * listing items,
 * getting a single item,
 * posting an item,
 * posting many items,
 * putting an item,
 * putting many items,
 * patching an item,
 * patching many items,
 * deleting an item,
 * deleting many items.
 *
 * @param path The route the websocket endpoint exists on.
 * @param getCollection A lambda that returns the field collection for the model given the calls principal
 */
@LightningServerDsl
inline fun <reified USER, reified T : HasId<ID>, reified ID : Comparable<ID>> ServerPath.restApi(
    noinline database: ()->Database,
    noinline getCollection: suspend Database.(principal: USER) -> FieldCollection<T>
) = restApi(AuthInfo(), serializerOrContextual(), serializerOrContextual(), database, getCollection)

/**
 * Creates a Restful API for the model provided.
 * The functionalities include:
 * listing items,
 * getting a single item,
 * posting an item,
 * posting many items,
 * putting an item,
 * putting many items,
 * patching an item,
 * patching many items,
 * deleting an item,
 * deleting many items.
 *
 * @param path The route the websocket endpoint exists on.
 * @param getCollection A lambda that returns the field collection for the model given the calls principal
 */
@LightningServerDsl
fun <USER, T : HasId<ID>, ID : Comparable<ID>> ServerPath.restApi(
    authInfo: AuthInfo<USER>,
    serializer: KSerializer<T>,
    idSerializer: KSerializer<ID>,
    database: ()->Database,
    getCollection: suspend Database.(principal: USER) -> FieldCollection<T>
) {
    val modelName = serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.')
    this.docName = modelName
    get.typed(
        authInfo = authInfo,
        inputType = Query.serializer(serializer),
        outputType = ListSerializer(serializer),
        summary = "List",
        description = "Gets a list of ${modelName}s.",
        errorCases = listOf(),
        implementation = { user: USER, input: Query<T> ->
            database().getCollection(user)
                .query(input)
                .toList()
        }
    )

    // This is used to GET a list objects, but rather than the query being in the parameter
    // it's in the POST body.
    post("query").typed(
        authInfo = authInfo,
        inputType = Query.serializer(serializer),
        outputType = ListSerializer(serializer),
        summary = "Query",
        description = "Gets a list of ${modelName}s that match the given query.",
        errorCases = listOf(),
        implementation = { user: USER, input: Query<T> ->
            database().getCollection(user)
                .query(input)
                .toList()
        }
    )

    // This is used get a single object with id of _id
    get("{id}").typed(
        authInfo = authInfo,
        inputType = Unit.serializer(),
        outputType = serializer,
        routeType = idSerializer,
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
            database().getCollection(user)
                .get(id)
                ?: throw NotFoundException()
        }
    )

    post("bulk").typed(
        authInfo = authInfo,
        inputType = ListSerializer(serializer),
        outputType = ListSerializer(serializer),
        summary = "Insert Bulk",
        description = "Creates multiple ${modelName}s at the same time.",
        errorCases = listOf(),
        successCode = HttpStatus.Created,
        implementation = { user: USER, values: List<T> ->
            database().getCollection(user)
                .insertMany(values)
        }
    )

    post("").typed(
        authInfo = authInfo,
        inputType = serializer,
        outputType = serializer,
        summary = "Insert",
        description = "Creates a new ${modelName}",
        errorCases = listOf(),
        successCode = HttpStatus.Created,
        implementation = { user: USER, value: T ->
            database().getCollection(user)
                .insertOne(value)
        }
    )

    post("{id}").typed(
        authInfo = authInfo,
        inputType = serializer,
        outputType = serializer,
        routeType = idSerializer,
        summary = "Upsert",
        description = "Creates or updates a ${modelName}",
        errorCases = listOf(),
        successCode = HttpStatus.Created,
        implementation = { user: USER, id: ID, value: T ->
            database().getCollection(user)
                .upsertOneById(id, value)
                .new
                ?: throw NotFoundException()
        }
    )

    // This is used replace many objects at once. This does make individual calls to the database. Kmongo does not have a many replace option.
    put("").typed(
        authInfo = authInfo,
        inputType = ListSerializer(serializer),
        outputType = ListSerializer(serializer),
        summary = "Bulk Replace",
        description = "Modifies many ${modelName}s at the same time by ID.",
        errorCases = listOf(),
        implementation = { user: USER, values: List<T> ->
            val db = database().getCollection(user)
            values.map { db.replaceOneById(it._id, it) }.mapNotNull { it.new }
        }
    )

    put("{id}").typed(
        authInfo = authInfo,
        inputType = serializer,
        outputType = serializer,
        routeType = idSerializer,
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
            database().getCollection(user)
                .replaceOneById(id, value)
                .new
                ?: throw NotFoundException()
        }
    )

    patch("bulk").typed(
        authInfo = authInfo,
        inputType = MassModification.serializer(serializer),
        outputType = Int.serializer(),
        summary = "Bulk Modify",
        description = "Modifies many ${modelName}s at the same time.  Returns the number of changed items.",
        errorCases = listOf(),
        implementation = { user: USER, input: MassModification<T> ->
            database().getCollection(user)
                .updateManyIgnoringResult(input)
        }
    )

    patch("{id}/delta").typed(
        authInfo = authInfo,
        inputType = Modification.serializer(serializer),
        outputType = EntryChange.serializer(serializer),
        routeType = idSerializer,
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
            database().getCollection(user)
                .updateOneById(id, input)
                .also { if (it.old == null && it.new == null) throw NotFoundException() }
        }
    )

    patch("{id}").typed(
        authInfo = authInfo,
        inputType = Modification.serializer(serializer),
        outputType = serializer,
        routeType = idSerializer,
        summary = "Modify With Diff",
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
            database().getCollection(user)
                .updateOneById(id, input)
                .also { if (it.old == null && it.new == null) throw NotFoundException() }
                .new!!
        }
    )

    post("bulk-delete").typed(
        authInfo = authInfo,
        inputType = Condition.serializer(serializer),
        outputType = Int.serializer(),
        summary = "Bulk Delete",
        description = "Deletes all matching ${modelName}s, returning the number of deleted items.",
        errorCases = listOf(),
        implementation = { user: USER, filter: Condition<T> ->
            database().getCollection(user)
                .deleteManyIgnoringOld(filter)
        }
    )

    delete("{id}").typed(
        authInfo = authInfo,
        inputType = Unit.serializer(),
        outputType = Unit.serializer(),
        routeType = idSerializer,
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
            if (!database().getCollection(user)
                    .deleteOneById(id)
            ) {
                throw NotFoundException()
            }
            Unit
        }
    )

    get("count").typed(
        authInfo = authInfo,
        inputType = Condition.serializer(serializer),
        outputType = Int.serializer(),
        summary = "Count",
        description = "Gets the total number of ${modelName}s matching the given condition.",
        errorCases = listOf(),
        implementation = { user: USER, condition: Condition<T> ->
            database().getCollection(user)
                .count(condition)
        }
    )
    post("count").typed(
        authInfo = authInfo,
        inputType = Condition.serializer(serializer),
        outputType = Int.serializer(),
        summary = "Count",
        description = "Gets the total number of ${modelName}s matching the given condition.",
        errorCases = listOf(),
        implementation = { user: USER, condition: Condition<T> ->
            database().getCollection(user)
                .count(condition)
        }
    )

    post("group-count").typed(
        authInfo = authInfo,
        inputType = GroupCountQuery.serializer(serializer),
        outputType = MapSerializer(String.serializer(), Int.serializer()),
        summary = "Group Count",
        description = "Gets the total number of ${modelName}s matching the given condition divided by group.",
        errorCases = listOf(),
        implementation = { user: USER, condition: GroupCountQuery<T> ->
            @Suppress("UNCHECKED_CAST")
            database().getCollection(user)
                .groupCount(condition.condition, condition.groupBy.property as KProperty1<T, Any?>)
                .mapKeys { it.key.toString() }
        }
    )

    post("aggregate").typed(
        authInfo = authInfo,
        inputType = AggregateQuery.serializer(serializer),
        outputType = Double.serializer().nullable,
        summary = "Aggregate",
        description = "Aggregates a property of ${modelName}s matching the given condition.",
        errorCases = listOf(),
        implementation = { user: USER, condition: AggregateQuery<T> ->
            @Suppress("UNCHECKED_CAST")
            database().getCollection(user)
                .aggregate(condition.aggregate, condition.condition, condition.property.property as KProperty1<T, Number>)
        }
    )

    post("group-aggregate").typed(
        authInfo = authInfo,
        inputType = GroupAggregateQuery.serializer(serializer),
        outputType = MapSerializer(String.serializer(), Double.serializer().nullable),
        summary = "Group Aggregate",
        description = "Aggregates a property of ${modelName}s matching the given condition divided by group.",
        errorCases = listOf(),
        implementation = { user: USER, condition: GroupAggregateQuery<T> ->
            @Suppress("UNCHECKED_CAST")
            database().getCollection(user)
                .groupAggregate(condition.aggregate, condition.condition,
                    condition.groupBy.property as KProperty1<T, Any?>, condition.property.property as KProperty1<T, Number>
                )
                .mapKeys { it.key.toString() }
        }
    )
}


