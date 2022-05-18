package com.lightningkite.ktorbatteries.db

import com.lightningkite.ktordb.FieldCollection
import com.lightningkite.ktordb.HasId
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.util.*


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
