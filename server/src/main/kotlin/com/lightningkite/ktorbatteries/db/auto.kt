package com.lightningkite.ktorbatteries.db

import com.lightningkite.ktordb.FieldCollection
import com.lightningkite.ktordb.HasId
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.util.*


/**
 * A shortcut function that sets up a restful api for the model provided, live updating websocket endpoints, and the development admin pages.
 *
 * @param path The route prefix for the collection
 * @param defaultItem A default instance of the model used in the admins create forms.
 * @param getCollection A lambda that returns the field collection for the model given the calls principal
 */
@KtorDsl
inline fun <reified USER, reified T : HasId<ID>, reified ID: Comparable<ID>> Route.autoCollection(
    path: String,
    noinline defaultItem: (principal: USER) -> T,
    noinline getCollection: suspend (principal: USER) -> FieldCollection<T>
) = route(path) {
    restApi(path = "rest", getCollection = getCollection)
    restApiWebsocket(path = "rest", getCollection = getCollection)
    adminPages(path = "admin", defaultItem = defaultItem, getCollection = getCollection)
}
