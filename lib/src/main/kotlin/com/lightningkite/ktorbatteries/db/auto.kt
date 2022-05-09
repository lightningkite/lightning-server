package com.lightningkite.ktorbatteries.db

import com.lightningkite.ktorkmongo.FieldCollection
import com.lightningkite.ktorkmongo.HasId
import com.lightningkite.ktorkmongo.WatchableFieldCollection
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.util.*


@KtorDsl
inline fun <reified USER : Principal, reified T : HasId> Route.autoCollection(
    path: String,
    noinline defaultItem: (principal: USER?) -> T,
    noinline getCollection: suspend (principal: USER?) -> FieldCollection<T>
) = route(path) {
    restApi(path = "rest", getCollection = getCollection)
    adminPages(path = "admin", defaultItem = defaultItem, getCollection = getCollection)
}

@KtorDsl
inline fun <reified USER : Principal, reified T : HasId> Route.autoCollectionWatchable(
    path: String,
    noinline defaultItem: (principal: USER?) -> T,
    noinline getCollection: suspend (principal: USER?) -> WatchableFieldCollection<T>
) = route(path) {
    restApi(path = "rest", getCollection = getCollection)
    restApiWebsocket(path = "rest", getCollection = getCollection)
    adminPages(path = "admin", defaultItem = defaultItem, getCollection = getCollection)
}