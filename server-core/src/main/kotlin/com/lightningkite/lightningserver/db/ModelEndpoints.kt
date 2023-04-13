package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup

abstract class ModelEndpoints<USER, T : HasId<ID>, ID : Comparable<ID>>(
    path: ServerPath,
    val info: ModelInfoWithDefault<USER, T, ID>
) : ServerPathGroup(path) {
    open val rest: ModelRestEndpoints<USER, T, ID> = ModelRestEndpoints(path("rest"), info)
    open val admin: ModelAdminEndpoints<USER, T, ID> = ModelAdminEndpoints(path("rest"), info)
}