@file:SharedCode
package com.lightningkite.lightningdb.mock

import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.ObserveModelApi
import com.lightningkite.lightningdb.Query
import com.lightningkite.rx.ValueSubject
import io.reactivex.rxjava3.core.Observable
import java.util.*

class MockObserveModelApi<Model : HasId<UUID>>(
    val table: MockTable<Model>,
) : ObserveModelApi<Model>() {
    override fun observe(query: Query<Model>): Observable<List<Model>> {
        return table.observe(query.condition).startWithItem(table.asList().filter { item -> query.condition.invoke(item) })
    }
}