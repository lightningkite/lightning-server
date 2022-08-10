package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.Database
import com.lightningkite.lightningdb.FieldCollection
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.AuthInfo
import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath


/**
 * Shortcut to create each of the endpoints required for the Auto Admin
 */
@LightningServerDsl
inline fun <reified USER, reified T : HasId<ID>, reified ID : Comparable<ID>> ServerPath.autoCollection(
    noinline database: ()-> Database,
    noinline defaultItem: (USER) -> T,
    noinline getCollection: suspend Database.(principal: USER) -> FieldCollection<T>
): Unit {
    path("rest").restApi(database, getCollection)
    path("admin").adminPages(database, defaultItem, getCollection)
}