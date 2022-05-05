package com.lightningkite.ktorbatteries.db

import com.lightningkite.ktorbatteries.typed.*
import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.flow.*
import com.lightningkite.ktorkmongo.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.routing.put
import io.ktor.util.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.*

fun String.toUuidOrBadRequest() = try {
    UUID.fromString(this)
} catch (e: Exception) {
    throw BadRequestException("ID ${this} could not be parsed as a UUID.")
}

// Creates writing end points for the model. POST, PUT, PATCH, DELETE,
@KtorDsl
inline fun <reified USER : Principal, reified T : HasId> Route.exposeWrite(
    collection: FieldCollection<T>,
    crossinline rules: suspend (principal: USER?, input: FieldCollection<T>) -> FieldCollection<T>
) {

    post(
        path = "bulk",
        summary = "Creates multiple ${T::class.simpleName}s at the same time.",
        errorCases = listOf(),
        successCode = HttpStatusCode.Created,
        implementation = { user: USER?, values: List<T> ->
            rules(user, collection)
                .insertMany(values)
        }
    )

    post(
        path = "",
        summary = "Creates a new ${T::class.simpleName}",
        errorCases = listOf(),
        successCode = HttpStatusCode.Created,
        implementation = { user: USER?, value: T ->
            rules(user, collection)
                .insertOne(value)
        }
    )

    // This is used replace many objects at once. This does make individual calls to the database. Kmongo does not have a many replace option.
    put(
        path = "",
        summary = "Modifies many ${T::class.simpleName}s at the same time by ID.",
        errorCases = listOf(),
        implementation = { user: USER?, values: List<T> ->
            val db = rules(user, collection)
            values.map { db.replaceOneById(it._id, it) }
        }
    )

    putItem(
        path = "",
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
                description = "The ID could not be parsed as a UUID."
            )
        ),
        implementation = { user: USER?, id: String, value: T ->
            rules(user, collection)
                .replaceOneById(id.toUuidOrBadRequest(), value)
                ?: throw NotFoundException()
        }
    )

    patch(
        path = "bulk",
        summary = "Modifies many ${T::class.simpleName}s at the same time.  Returns the number of changed items.",
        errorCases = listOf(),
        implementation = { user: USER?, input: MassModification<T> ->
            rules(user, collection)
                .updateMany(input)
        }
    )

    patchItem(
        path = "",
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
                description = "The ID could not be parsed as a UUID."
            )
        ),
        implementation = { user: USER?, id: String, input: Modification<T> ->
            rules(user, collection)
                .findOneAndUpdateById(id.toUuidOrBadRequest(), input)
                .also { if (it.old == null && it.new == null) throw NotFoundException() }
        }
    )

    delete(
        path = "bulk",
        summary = "Deletes all matching ${T::class.simpleName}s, returning the number of deleted items.",
        errorCases = listOf(),
        implementation = { user: USER?, filter: Condition<T> ->
            rules(user, collection)
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
                description = "The ID could not be parsed as a UUID."
            )
        ),
        implementation = { user: USER?, id: String, _: Unit ->
            if (!rules(user, collection)
                    .deleteOneById(id.toUuidOrBadRequest())
            ) {
                throw NotFoundException()
            }
            Unit
        }
    )
}

// Creates Reading endpoints for the model. GET, and listing
@KtorDsl
inline fun <reified USER : Principal, reified T : HasId> Route.exposeRead(
    collection: FieldCollection<T>,
    crossinline rules: suspend (principal: USER?, input: FieldCollection<T>) -> FieldCollection<T>
) {
    get(
        path = "",
        summary = "Gets a list of ${T::class.simpleName}s.",
        errorCases = listOf(),
        implementation = { user: USER?, input: Query<T> ->
            rules(user, collection)
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
            rules(user, collection)
                .query(input)
                .toList()
        }
    )

    // This is used get a single object with id of _id
    getItem(
        path = "",
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
                description = "The ID could not be parsed as a UUID."
            )
        ),
        implementation = { user: USER?, id: String, input: Unit ->
            rules(user, collection)
                .get(id.toUuidOrBadRequest())
                ?: throw NotFoundException()
        }
    )
}

// Creates websocket listening end points for a model
@OptIn(ExperimentalCoroutinesApi::class)
@KtorDsl
inline fun <reified USER : Principal, reified T : HasId> Route.exposeWebSocket(
    collection: WatchableFieldCollection<T>,
    crossinline rules: suspend (principal: USER?, input: FieldCollection<T>) -> FieldCollection<T>
) {
    jsonWebSocket<ListChange<T>, Query<T>> {
        val secured = rules(
            call.principal(),
            collection
        ) as WatchableFieldCollection // collection.secure(secure(call.principal<USER>()))
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
inline fun <reified USER : Principal, reified T : HasId> Route.exposeReadWrite(
    collection: FieldCollection<T>,
    crossinline rules: suspend (principal: USER?, input: FieldCollection<T>) -> FieldCollection<T>

) {
    this.exposeRead(collection, rules)
    this.exposeWrite(collection, rules)
}

// Calls all three of the endpoint type functions
@KtorDsl
inline fun <reified USER : Principal, reified T : HasId> Route.exposeAll(
    collection: WatchableFieldCollection<T>,
    crossinline rules: suspend (principal: USER?, input: FieldCollection<T>) -> FieldCollection<T>

) {
    this.exposeRead(collection, rules)
    this.exposeWrite(collection, rules)
    this.exposeWebSocket(collection, rules)
}


// Calls Both read/listening end points. restRead and websockets.
@KtorDsl
inline fun <reified USER : Principal, reified T : HasId> Route.exposeReadAndWebSocket(
    collection: WatchableFieldCollection<T>,
    crossinline rules: suspend (principal: USER?, input: FieldCollection<T>) -> FieldCollection<T>
) {
    this.exposeRead(collection, rules)
    this.exposeWebSocket(collection, rules)
}

