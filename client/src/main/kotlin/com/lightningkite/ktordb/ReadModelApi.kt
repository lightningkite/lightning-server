@file:SharedCode
package com.lightningkite.ktordb

import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.SharedCode
import io.reactivex.rxjava3.core.Single

abstract class ReadModelApi<Model : IsCodableAndHashable> {
    abstract fun list(query: Query<Model>): Single<List<Model>>
    abstract fun get(id: UUIDFor<Model>): Single<Model>
}