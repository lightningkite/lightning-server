@file:SharedCode
package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.SharedCode
import io.reactivex.rxjava3.core.Observable
import java.util.*

abstract class ObserveModelApi<Model: IsCodableAndHashable> {
    abstract fun observe(query: Query<Model>): Observable<List<Model>>
}