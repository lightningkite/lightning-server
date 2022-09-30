@file:SharedCode
package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.SharedCode
import io.reactivex.rxjava3.core.Single
import java.util.*

abstract class WriteModelApi<Model : IsCodableAndHashable> {
    abstract fun post(value: Model): Single<Model>
    abstract fun postBulk(values:List<Model>): Single<List<Model>>
    abstract fun put(value: Model): Single<Model>
    abstract fun putBulk(values:List<Model>): Single<List<Model>>
    abstract fun patch(id: UUIDFor<Model>, modification: Modification<Model>): Single<Model>
    abstract fun patchBulk(modification: MassModification<Model>): Single<Long>
    abstract fun delete(id: UUIDFor<Model>): Single<Unit>
    abstract fun deleteBulk(condition: Condition<Model>): Single<Unit>
}