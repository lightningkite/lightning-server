package com.lightningkite.ktorkmongo

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.flow.*


// Creates writing end points for the model. POST, PUT, PATCH, DELETE,
@ContextDsl
inline fun <reified T : HasId> Route.exposeWrite(
    collection: FieldCollection<T>,
    crossinline getRules: suspend ApplicationCall.() -> SecurityRules<T>
) {

    // This is used to create many new objects at once
    post("bulk") {
        val values: List<T> = call.receiveSafe()
        if (values.isNotEmpty()) {
            collection
                .secure(call.getRules())
                .insertMany(values)
                .let { call.respond<List<T>>(HttpStatusCode.Created, it.data) }
        } else {
            throw BadFormatException("Empty list provided.")
        }
    }

    // This is used to create a new object
    post {
        collection
            .secure(call.getRules())
            .insertOne(call.receiveSafe())
            .let { call.respond<T>(HttpStatusCode.Created, it.data) }
    }.htmlJsonRoute()

    // This is used replace many objects at once. This does make individual calls to the database. Kmongo does not have a many replace option.
    put("bulk") {
        val values: List<T> = call.receiveSafe()
        if (values.isNotEmpty()) {
            collection
                .secure(call.getRules())
                .let { c ->
                    values.map { c.replaceOneById(it._id, it) }
                }
                .let { call.respond<List<T>>(it.filter { it.result.matchedCount > 0 }.map { it.data }) }
        } else {
            throw BadFormatException("Empty list provided.")
        }
    }.htmlJsonRoute()

    // This is used replace a single object with id of _id
    put("{_id}") {
        collection
            .secure(call.getRules())
            .replaceOneById(call.parameters["_id"]!!.toUUID(), call.receiveSafe())
            .takeIf { it.result.matchedCount > 0 }
            ?.let { call.respond<T>(it.data) }
            ?: call.respond(HttpStatusCode.NotFound)
    }.htmlJsonRoute()

    // This is used to patch many objects at once
    patch("bulk") {
        collection
            .secure(call.getRules())
            .updateMany(call.receiveSafe())
            .let { call.respond<Long>(it.matchedCount) }
    }.htmlJsonRoute()

    // This is used to patch a single object with id of _id
    patch("{_id}") {
        val c = collection
            .secure(call.getRules())
        val id = call.parameters["_id"]!!.toUUID()
        c
            .findOneAndUpdateById(id, call.receiveSafe())
            ?.let { call.respond<T>(it) }
            ?: call.respond(HttpStatusCode.NotFound)
    }.htmlJsonRoute()

    // This is used to delete many objects at once.
    delete("bulk") {
        collection
            .secure(call.getRules())
            .deleteMany(call.receiveSafe())
        call.respond(HttpStatusCode.NoContent)
    }.htmlJsonRoute()

    // This is used to delete a single object with id of _id
    delete("{_id}") {
        collection
            .secure(call.getRules())
            .deleteOneById(call.parameters["_id"]!!.toUUID())
        call.respond(HttpStatusCode.NoContent)
    }.htmlJsonRoute()
}

// Creates Reading endpoints for the model. GET, and listing
@ContextDsl
inline fun <reified T : HasId> Route.exposeRead(
    collection: FieldCollection<T>,
    crossinline getRules: suspend ApplicationCall.() -> SecurityRules<T>
) {

    // Get a list of objects
    get {
        collection
            .secure(call.getRules())
            .query(call.parameters.toQuery())
            .let { call.respond<List<T>>(it.toList()) }
    }.htmlJsonRoute()

    // This is used to GET a list objects, but rather than the query being in the parameter
    // it's in the POST body.
    post("query") {
        collection
            .secure(call.getRules())
            .query(call.receiveSafe())
            .let { call.respond<List<T>>(it.toList()) }
    }.htmlJsonRoute()

    // This is used get a single object with id of _id
    get("{_id}") {
        collection
            .secure(call.getRules())
            .get(call.parameters["_id"]!!.toUUID())
            ?.let { call.respond<T>(it) }
            ?: call.respond(HttpStatusCode.NotFound)
    }

}

// Creates websocket listening end points for a model
@ContextDsl
inline fun <reified T : HasId> Route.exposeWebSocket(
    collection: FieldCollection<T>,
    crossinline getRules: suspend ApplicationCall.() -> SecurityRules<T>,
) {
    jsonWebSocket<ListChange<T>, Query<T>> {
        val secure = collection.secure(getRules(this.call))
        incoming.flatMapLatest { query ->
            secure.watch(query.condition)
                .map { it.listChange() }
                .onStart {
                    val startItems = secure.query(query).toList()
                    collection.coroutineCollection.collection.insertIntoCache(startItems)
                    emit(ListChange(wholeList = startItems))
                }
        }.collect(send)
    }
}

// Calls all three of the endpoint type functions
@ContextDsl
inline fun <reified T : HasId> Route.exposeAll(
    collection: FieldCollection<T>,
    crossinline getRules: suspend ApplicationCall.() -> SecurityRules<T>

) {
    this.exposeRead(collection, getRules)
    this.exposeWrite(collection, getRules)
    this.exposeWebSocket(collection, getRules)
}


// Calls Both read/listening end points. restRead and websockets.
@ContextDsl
inline fun <reified T : HasId> Route.exposeReadAndWebSocket(
    collection: FieldCollection<T>,
    crossinline getRules: suspend ApplicationCall.() -> SecurityRules<T>
) {
    this.exposeRead(collection, getRules)
    this.exposeWebSocket(collection, getRules)
}


fun <T : HasId> Parameters.toQuery(): Query<T> = Query()
