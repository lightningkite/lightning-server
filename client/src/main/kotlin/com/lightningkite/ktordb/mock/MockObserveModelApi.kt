@file:SharedCode
package com.lightningkite.ktordb.mock

import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.ktordb.HasId
import com.lightningkite.ktordb.ObserveModelApi
import com.lightningkite.ktordb.Query
import com.lightningkite.rx.ValueSubject
import io.reactivex.rxjava3.core.Observable

class MockObserveModelApi<Model : HasId>(
    val table: MockTable<Model>,
) : ObserveModelApi<Model>() {
    override fun observe(query: Query<Model>): Observable<List<Model>> {
        return table.observe(query.condition).startWithItem(table.asList().filter { item -> query.condition.invoke(item) })
    }
}