@file:SharedCode
package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.SharedCode
import io.reactivex.rxjava3.core.Single
import java.util.*

abstract class ReadModelApi<Model : IsCodableAndHashable> {
    abstract fun list(query: Query<Model>): Single<List<Model>>
    abstract fun get(id: UUIDFor<Model>): Single<Model>
}