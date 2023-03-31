package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.Database
import com.lightningkite.lightningdb.FieldCollection
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.collection
import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath


@LightningServerDsl
@Deprecated("")
inline fun <reified USER, reified T : HasId<ID>, reified ID : Comparable<ID>> ServerPath.autoCollection(
    noinline database: () -> Database,
    noinline defaultItem: (USER) -> T,
    noinline getCollection: suspend Database.(principal: USER) -> FieldCollection<T>
): Unit {
    path("rest").restApi(database, getCollection)
    path("admin").adminPages(database, defaultItem, getCollection)
}

@LightningServerDsl
@Deprecated("")
inline fun <reified USER, reified T : HasId<ID>, reified ID : Comparable<ID>> ServerPath.adminPages(
    noinline database: () -> Database,
    noinline defaultItem: (USER) -> T,
    noinline getCollection: suspend Database.(principal: USER) -> FieldCollection<T>
) = ModelAdminEndpoints(
    this, ModelInfoWithDefault(
        getCollection = { database().collection() },
        forUser = { getCollection(database(), it) },
        defaultItem = defaultItem
    )
)

@LightningServerDsl
@Deprecated("")
inline fun <reified USER, reified T : HasId<ID>, reified ID : Comparable<ID>> ServerPath.restApi(
    noinline database: () -> Database,
    noinline getCollection: suspend Database.(principal: USER) -> FieldCollection<T>
) = ModelRestEndpoints<USER, T, ID>(this, ModelInfo(
    getCollection = { database().collection() },
    forUser = { getCollection(database(), it) }
))