package com.lightningkite.ktorbatteries.db

import com.lightningkite.ktorbatteries.routes.docName
import com.lightningkite.ktorbatteries.serialization.Serialization
import com.lightningkite.ktorbatteries.typed.*
import io.ktor.http.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.*
import com.lightningkite.ktordb.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.util.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.properties.decodeFromStringMap
import kotlinx.serialization.properties.encodeToStringMap
import java.util.*
import kotlin.reflect.KProperty1

/**
 * Creates a websocket end point to receive live updates for the model.
 *
 * @param path The route the websocket endpoint exists on.
 * @param getCollection A lambda that returns the field collection for the model given the calls principal
 */
@OptIn(ExperimentalCoroutinesApi::class)
@KtorDsl
inline fun <reified USER, reified T : HasId<ID>, reified ID: Comparable<ID>> Route.restApiWebsocket(
    path: String = "",
    crossinline getCollection: suspend (principal: USER) -> FieldCollection<T>
) = route(path) {
    val modelName = T::class.simpleName
    this.docName = modelName
    apiWebsocket<USER, Query<T>, ListChange<T>>(
        path = "",
        summary = "Watch",
        description = "Gets a changing list of ${modelName}s that match the given query.",
        errorCases = listOf(),
    ) { user ->
        val secured = getCollection(user)
        incoming.flatMapLatest { query ->
            secured.watch(query.condition)
                .map { it.listChange() }
                .onStart {
                    val startItems = secured.query(query).toList()
                    emit(ListChange(wholeList = startItems))
                }
        }.collect { send(it) }
    }
}


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
@KtorDsl
inline fun <reified USER, reified T : HasId<ID>, reified ID : Comparable<ID>> Route.restApi(
    path: String = "",
    crossinline getCollection: suspend (principal: USER) -> FieldCollection<T>
) = route(path) {
    val modelName = T::class.simpleName
    this.docName = modelName
    get(
        path = "",
        summary = "List",
        description = "Gets a list of ${modelName}s.",
        errorCases = listOf(),
        implementation = { user: USER, input: Query<T> ->
            getCollection(user)
                .query(input)
                .toList()
        }
    )

    // This is used to GET a list objects, but rather than the query being in the parameter
    // it's in the POST body.
    post(
        path = "query",
        summary = "Query",
        description = "Gets a list of ${modelName}s that match the given query.",
        errorCases = listOf(),
        implementation = { user: USER, input: Query<T> ->
            getCollection(user)
                .query(input)
                .toList()
        }
    )

    // This is used get a single object with id of _id
    getItem(
        postIdPath = "",
        summary = "Detail",
        description = "Gets a single ${modelName} by ID.",
        errorCases = listOf(
            ApiEndpoint.ErrorCase(
                status = HttpStatusCode.NotFound,
                internalCode = 0,
                description = "There was no known object by that ID."
            ),
            ApiEndpoint.ErrorCase(
                status = HttpStatusCode.BadRequest,
                internalCode = 0,
                description = "The ID could not be parsed."
            )
        ),
        implementation = { user: USER, id: ID, input: Unit ->
            getCollection(user)
                .get(id)
                ?: throw NotFoundException()
        }
    )

    post(
        path = "bulk",
        summary = "Insert Bulk",
        description = "Creates multiple ${modelName}s at the same time.",
        errorCases = listOf(),
        successCode = HttpStatusCode.Created,
        implementation = { user: USER, values: List<T> ->
            getCollection(user)
                .insertMany(values)
        }
    )

    post(
        path = "",
        summary = "Insert",
        description = "Creates a new ${modelName}",
        errorCases = listOf(),
        successCode = HttpStatusCode.Created,
        implementation = { user: USER, value: T ->
            getCollection(user)
                .insertOne(value)
        }
    )

    postItem(
        postIdPath = "",
        summary = "Upsert",
        description = "Creates or updates a ${modelName}",
        errorCases = listOf(),
        successCode = HttpStatusCode.Created,
        implementation = { user: USER, id: ID, value: T ->
            getCollection(user)
                .upsertOneById(id, value)
                ?: throw NotFoundException()
        }
    )

    // This is used replace many objects at once. This does make individual calls to the database. Kmongo does not have a many replace option.
    put(
        path = "",
        summary = "Bulk Replace",
        description = "Modifies many ${modelName}s at the same time by ID.",
        errorCases = listOf(),
        implementation = { user: USER, values: List<T> ->
            val db = getCollection(user)
            values.map { db.replaceOneById(it._id, it) }
        }
    )

    putItem(
        postIdPath = "",
        summary = "Replace",
        description = "Replaces a single ${modelName} by ID.",
        errorCases = listOf(
            ApiEndpoint.ErrorCase(
                status = HttpStatusCode.NotFound,
                internalCode = 0,
                description = "There was no known object by that ID."
            ),
            ApiEndpoint.ErrorCase(
                status = HttpStatusCode.BadRequest,
                internalCode = 0,
                description = "The ID could not be parsed."
            )
        ),
        implementation = { user: USER, id: ID, value: T ->
            getCollection(user)
                .replaceOneById(id, value)
                ?: throw NotFoundException()
        }
    )

    patch(
        path = "bulk",
        summary = "Bulk Modify",
        description = "Modifies many ${modelName}s at the same time.  Returns the number of changed items.",
        errorCases = listOf(),
        implementation = { user: USER, input: MassModification<T> ->
            getCollection(user)
                .updateMany(input)
        }
    )

    patchItem(
        postIdPath = "delta",
        summary = "Modify",
        description = "Modifies a ${modelName} by ID, returning both the previous value and new value.",
        errorCases = listOf(
            ApiEndpoint.ErrorCase(
                status = HttpStatusCode.NotFound,
                internalCode = 0,
                description = "There was no known object by that ID."
            ),
            ApiEndpoint.ErrorCase(
                status = HttpStatusCode.BadRequest,
                internalCode = 0,
                description = "The ID could not be parsed."
            )
        ),
        implementation = { user: USER, id: ID, input: Modification<T> ->
            getCollection(user)
                .findOneAndUpdateById(id, input)
                .also { if (it.old == null && it.new == null) throw NotFoundException() }
        }
    )

    patchItem(
        postIdPath = "",
        summary = "Modify With Diff",
        description = "Modifies a ${modelName} by ID, returning both the previous value and new value.",
        errorCases = listOf(
            ApiEndpoint.ErrorCase(
                status = HttpStatusCode.NotFound,
                internalCode = 0,
                description = "There was no known object by that ID."
            ),
            ApiEndpoint.ErrorCase(
                status = HttpStatusCode.BadRequest,
                internalCode = 0,
                description = "The ID could not be parsed."
            )
        ),
        implementation = { user: USER, id: ID, input: Modification<T> ->
            getCollection(user)
                .findOneAndUpdateById(id, input)
                .also { if (it.old == null && it.new == null) throw NotFoundException() }
                .new
        }
    )

    post(
        path = "bulk-delete",
        summary = "Bulk Delete",
        description = "Deletes all matching ${modelName}s, returning the number of deleted items.",
        errorCases = listOf(),
        implementation = { user: USER, filter: Condition<T> ->
            getCollection(user)
                .deleteMany(filter)
        }
    )

    deleteItem(
        path = "",
        summary = "Delete",
        description = "Deletes a ${modelName} by id.",
        errorCases = listOf(
            ApiEndpoint.ErrorCase(
                status = HttpStatusCode.NotFound,
                internalCode = 0,
                description = "There was no known object by that ID."
            ),
            ApiEndpoint.ErrorCase(
                status = HttpStatusCode.BadRequest,
                internalCode = 0,
                description = "The ID could not be parsed."
            )
        ),
        implementation = { user: USER, id: ID, _: Unit ->
            if (!getCollection(user)
                    .deleteOneById(id)
            ) {
                throw NotFoundException()
            }
            Unit
        }
    )

    get(
        path = "count",
        summary = "Count",
        description = "Gets the total number of ${modelName}s matching the given condition.",
        errorCases = listOf(),
        implementation = { user: USER, condition: Condition<T> ->
            getCollection(user)
                .count(condition)
        }
    )
    post(
        path = "count",
        summary = "Count",
        description = "Gets the total number of ${modelName}s matching the given condition.",
        errorCases = listOf(),
        implementation = { user: USER, condition: Condition<T> ->
            getCollection(user)
                .count(condition)
        }
    )

    post(
        path = "group-count",
        summary = "Group Count",
        description = "Gets the total number of ${modelName}s matching the given condition divided by group.",
        errorCases = listOf(),
        implementation = { user: USER, condition: GroupCountQuery<T> ->
            @Suppress("UNCHECKED_CAST")
            getCollection(user)
                .groupCount(condition.condition, condition.groupBy as KProperty1<T, Any?>)
                .mapKeys { it.key.toString() }
        }
    )

    post(
        path = "aggregate",
        summary = "Aggregate",
        description = "Aggregates a property of ${modelName}s matching the given condition.",
        errorCases = listOf(),
        implementation = { user: USER, condition: AggregateQuery<T> ->
            @Suppress("UNCHECKED_CAST")
            getCollection(user)
                .aggregate(condition.aggregate, condition.condition, condition.property as KProperty1<T, Number>)
        }
    )

    post(
        path = "group-aggregate",
        summary = "Group Aggregate",
        description = "Aggregates a property of ${modelName}s matching the given condition divided by group.",
        errorCases = listOf(),
        implementation = { user: USER, condition: GroupAggregateQuery<T> ->
            getCollection(user)
                .groupAggregate(condition.aggregate, condition.condition,
                    condition.groupBy as KProperty1<T, Any?>, condition.property as KProperty1<T, Number>
                )
                .mapKeys { it.key.toString() }
        }
    )
}


