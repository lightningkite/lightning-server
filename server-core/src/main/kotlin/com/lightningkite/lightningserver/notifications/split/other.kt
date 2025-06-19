package com.lightningkite.lightningserver.notifications.split

import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.ModificationBuilder
import com.lightningkite.lightningdb.SortBuilder
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.ModelInfo
import com.lightningkite.lightningserver.db.ModelRestEndpoints
import com.lightningkite.serialization.DataClassPath
import com.lightningkite.serialization.DataClassPathSelf


abstract class InfoAndEndpoints<USER : HasId<UID>, UID : Comparable<UID>, T : HasId<ID>, ID : Comparable<ID>>(
    path: ServerPath,
    val info: ModelInfo<USER, T, ID>
) : ModelInfo<USER, T, ID> by info, ServerPathGroup(path) {
    val restPath = path("rest")
    val rest = ModelRestEndpoints(restPath, this)

    private val self = DataClassPathSelf(info.serialization.serializer)

    // These are needed because of a kotlin typing error: "Non-reified type parameters with recursive bounds are not supported yet"
    fun condition(condition: (DataClassPath<T, T>)->Condition<T>) = condition(self)
    fun modification(modification: ModificationBuilder<T>.(DataClassPath<T, T>)->Unit) = ModificationBuilder<T>().apply { modification(self) }.build()
    fun sort(setup: SortBuilder<T>.(DataClassPath<T, T>)->Unit) = SortBuilder<T>().apply { setup(self) }.build()
}