@file:SharedCode
package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.IsCodableAndHashableNotNull
import com.lightningkite.khrysalis.SharedCode
import io.reactivex.rxjava3.core.Single
import java.util.*

abstract class WriteModelApi<Model : IsCodableAndHashableNotNull> {
    abstract fun post(value: Model): Single<Model>
    abstract fun postBulk(values: List<Model>): Single<List<Model>>
    abstract fun upsert(value: Model, id: UUID): Single<Model>
    abstract fun put(value: Model): Single<Model>
    abstract fun putBulk(values:List<Model>): Single<List<Model>>
    abstract fun patch(id: UUID, modification: Modification<Model>): Single<Model>
    abstract fun patchBulk(modification: MassModification<Model>): Single<Long>
    abstract fun delete(id: UUID): Single<Unit>
    abstract fun deleteBulk(condition: Condition<Model>): Single<Unit>
}