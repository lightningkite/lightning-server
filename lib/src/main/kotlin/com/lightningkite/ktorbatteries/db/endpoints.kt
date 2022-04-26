package com.lightningkite.ktorbatteries.db

import com.lightningkite.ktorbatteries.typed.*
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.flow.*
import com.lightningkite.ktorkmongo.*
import io.ktor.auth.*
import io.ktor.features.*
import io.ktor.request.*
import io.ktor.routing.put
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.*

fun String.toUuidOrBadRequest() = try { UUID.fromString(this) } catch(e: Exception) { throw BadRequestException("ID ${this} could not be parsed as a UUID.") }

// Creates writing end points for the model. POST, PUT, PATCH, DELETE,
@ContextDsl
inline fun <reified USER: Principal, reified T : HasId> Route.exposeWrite(
    collection: FieldCollection<T>,
    crossinline secure: suspend (USER?) -> SecurityRules<T>
) {

    post(
        path = "bulk",
        summary = "Creates multiple ${T::class.simpleName}s at the same time.",
        errorCases = listOf(),
        successCode = HttpStatusCode.Created,
        implementation = { user: USER?, values: List<T> ->
            collection
                .secure(secure(user))
                .insertMany(values)
        }
    )

    post(
        path = "",
        summary = "Creates a new ${T::class.simpleName}",
        errorCases = listOf(),
        successCode = HttpStatusCode.Created,
        implementation = { user: USER?, value: T ->
            collection
                .secure(secure(user))
                .insertOne(value)
        }
    )

    // This is used replace many objects at once. This does make individual calls to the database. Kmongo does not have a many replace option.
    put(
        path = "",
        summary = "Modifies many ${T::class.simpleName}s at the same time by ID.",
        errorCases = listOf(),
        implementation = { user: USER?, values: List<T> ->
            val db = collection
                .secure(secure(user))
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
            collection
                .secure(secure(user))
                .replaceOneById(id.toUuidOrBadRequest(), value)
                ?: throw NotFoundException()
        }
    )

    patch(
        path = "bulk",
        summary = "Modifies many ${T::class.simpleName}s at the same time.  Returns the number of changed items.",
        errorCases = listOf(),
        implementation = { user: USER?, input: MassModification<T> ->
            collection
                .secure(secure(user))
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
            collection
                .secure(secure(user))
                .findOneAndUpdateById(id.toUuidOrBadRequest(), input)
                .also { if(it.old == null && it.new == null) throw NotFoundException() }
        }
    )

    delete(
        path = "bulk",
        summary = "Deletes all matching ${T::class.simpleName}s, returning the number of deleted items.",
        errorCases = listOf(),
        implementation = { user: USER?, filter: Condition<T> ->
            collection
                .secure(secure(user))
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
            if(!collection
                .secure(secure(user))
                .deleteOneById(id.toUuidOrBadRequest())) {
                throw NotFoundException()
            }
            Unit
        }
    )
}

// Creates Reading endpoints for the model. GET, and listing
@ContextDsl
inline fun <reified USER: Principal, reified T : HasId> Route.exposeRead(
    collection: FieldCollection<T>,
    crossinline secure: suspend (USER?) -> SecurityRules<T>
) {
    get(
        path = "",
        summary = "Gets a list of ${T::class.simpleName}s.",
        errorCases = listOf(),
        implementation = { user: USER?, input: Query<T> ->
            collection
                .secure(secure(user))
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
            collection
                .secure(secure(user))
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
            collection
                .secure(secure(user))
                .get(id.toUuidOrBadRequest())
                ?: throw NotFoundException()
        }
    )
}

// Creates websocket listening end points for a model
@OptIn(ExperimentalCoroutinesApi::class)
@ContextDsl
inline fun <reified USER: Principal, reified T : HasId> Route.exposeWebSocket(
    collection: WatchableFieldCollection<T>,
    crossinline secure: suspend (USER?) -> SecurityRules<T>
) {
    jsonWebSocket<ListChange<T>, Query<T>> {
        val secured = collection.secure(secure(call.principal<USER>()))
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
@ContextDsl
inline fun <reified USER: Principal, reified T : HasId> Route.exposeReadWrite(
    collection: FieldCollection<T>,
    crossinline secure: suspend (USER?) -> SecurityRules<T>

) {
    this.exposeRead(collection, secure)
    this.exposeWrite(collection, secure)
}

// Calls all three of the endpoint type functions
@ContextDsl
inline fun <reified USER: Principal, reified T : HasId> Route.exposeAll(
    collection: WatchableFieldCollection<T>,
    crossinline secure: suspend (USER?) -> SecurityRules<T>

) {
    this.exposeRead(collection, secure)
    this.exposeWrite(collection, secure)
    this.exposeWebSocket(collection, secure)
}


// Calls Both read/listening end points. restRead and websockets.
@ContextDsl
inline fun <reified USER: Principal, reified T : HasId> Route.exposeReadAndWebSocket(
    collection: WatchableFieldCollection<T>,
    crossinline secure: suspend (USER?) -> SecurityRules<T>
) {
    this.exposeRead(collection, secure)
    this.exposeWebSocket(collection, secure)
}

