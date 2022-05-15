package com.lightningkite.ktorbatteries.db

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

inline fun <reified T: Comparable<T>> String.parseUrlPartOrBadRequest(): T = try {
    Serialization.properties.decodeFromStringMap<ForeignKey<HasId<T>, T>>(mapOf("id" to this)).id
} catch(e: Exception) {
    throw BadRequestException("ID ${this} could not be parsed as a ${T::class.simpleName}.")
}

// Creates websocket listening end points for a model
@OptIn(ExperimentalCoroutinesApi::class)
@KtorDsl
inline fun <reified USER : Principal, reified T : HasId<ID>, reified ID: Comparable<ID>> Route.restApiWebsocket(
    path: String = "",
    crossinline getCollection: suspend (principal: USER?) -> FieldCollection<T>
) {
    apiWebsocket<USER, Query<T>, ListChange<T>>(
        path = path,
        summary = "Gets a changing list of ${T::class.simpleName}s that match the given query.",
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

// Calls all three of the endpoint type functions
@KtorDsl
inline fun <reified USER : Principal, reified T : HasId<ID>, reified ID: Comparable<ID>> Route.restApi(
    path: String = "",
    crossinline getCollection: suspend (principal: USER?) -> FieldCollection<T>
) = route(path) {
    get(
        path = "",
        summary = "Gets a list of ${T::class.simpleName}s.",
        errorCases = listOf(),
        implementation = { user: USER?, input: Query<T> ->
            getCollection(user)
                .query(input)
                .toList()
        }
    )

    // This is used to GET a list objects, but rather than the query being in the parameter
    // it's in the POST body.
    post(
        path = "query",
        summary = "Gets a list of ${T::class.simpleName}s that match the given query.",
        errorCases = listOf(),
        implementation = { user: USER?, input: Query<T> ->
            getCollection(user)
                .query(input)
                .toList()
        }
    )

    // This is used get a single object with id of _id
    getItem(
        postIdPath = "",
        summary = "Gets a single ${T::class.simpleName} by ID.",
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
        implementation = { user: USER?, id: String, input: Unit ->
            getCollection(user)
                .get(id.parseUrlPartOrBadRequest())
                ?: throw NotFoundException()
        }
    )

    post(
        path = "bulk",
        summary = "Creates multiple ${T::class.simpleName}s at the same time.",
        errorCases = listOf(),
        successCode = HttpStatusCode.Created,
        implementation = { user: USER?, values: List<T> ->
            getCollection(user)
                .insertMany(values)
        }
    )

    post(
        path = "",
        summary = "Creates a new ${T::class.simpleName}",
        errorCases = listOf(),
        successCode = HttpStatusCode.Created,
        implementation = { user: USER?, value: T ->
            getCollection(user)
                .insertOne(value)
        }
    )

    postItem(
        postIdPath = "",
        summary = "Creates or updates a ${T::class.simpleName}",
        errorCases = listOf(),
        successCode = HttpStatusCode.Created,
        implementation = { user: USER?, id: String, value: T ->
            getCollection(user)
                .upsertOneById(id.parseUrlPartOrBadRequest(), value)
                ?: throw NotFoundException()
        }
    )

    // This is used replace many objects at once. This does make individual calls to the database. Kmongo does not have a many replace option.
    put(
        path = "",
        summary = "Modifies many ${T::class.simpleName}s at the same time by ID.",
        errorCases = listOf(),
        implementation = { user: USER?, values: List<T> ->
            val db = getCollection(user)
            values.map { db.replaceOneById(it._id, it) }
        }
    )

    putItem(
        postIdPath = "",
        summary = "Replaces a single ${T::class.simpleName} by ID.",
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
        implementation = { user: USER?, id: String, value: T ->
            getCollection(user)
                .replaceOneById(id.parseUrlPartOrBadRequest(), value)
                ?: throw NotFoundException()
        }
    )

    patch(
        path = "bulk",
        summary = "Modifies many ${T::class.simpleName}s at the same time.  Returns the number of changed items.",
        errorCases = listOf(),
        implementation = { user: USER?, input: MassModification<T> ->
            getCollection(user)
                .updateMany(input)
        }
    )

    patchItem(
        postIdPath = "delta",
        summary = "Modifies a ${T::class.simpleName} by ID, returning both the previous value and new value.",
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
        implementation = { user: USER?, id: String, input: Modification<T> ->
            getCollection(user)
                .findOneAndUpdateById(id.parseUrlPartOrBadRequest(), input)
                .also { if (it.old == null && it.new == null) throw NotFoundException() }
        }
    )

    patchItem(
        postIdPath = "",
        summary = "Modifies a ${T::class.simpleName} by ID, returning both the previous value and new value.",
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
        implementation = { user: USER?, id: String, input: Modification<T> ->
            getCollection(user)
                .findOneAndUpdateById(id.parseUrlPartOrBadRequest(), input)
                .also { if (it.old == null && it.new == null) throw NotFoundException() }
                .new
        }
    )

    post(
        path = "bulk-delete",
        summary = "Deletes all matching ${T::class.simpleName}s, returning the number of deleted items.",
        errorCases = listOf(),
        implementation = { user: USER?, filter: Condition<T> ->
            getCollection(user)
                .deleteMany(filter)
        }
    )

    deleteItem(
        path = "",
        summary = "Deletes a ${T::class.simpleName} by id.",
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
        implementation = { user: USER?, id: String, _: Unit ->
            if (!getCollection(user)
                    .deleteOneById(id.parseUrlPartOrBadRequest())
            ) {
                throw NotFoundException()
            }
            Unit
        }
    )
}


