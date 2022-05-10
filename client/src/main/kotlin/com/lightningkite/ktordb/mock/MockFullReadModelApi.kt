@file:SharedCode
package com.lightningkite.ktordb.mock

import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.ktordb.*
import com.lightningkite.ktordb.HasId

class MockFullReadModelApi<Model : HasId>(
    val table: MockTable<Model>,
) : FullReadModelApi<Model>() {
    override val read: ReadModelApi<Model> = MockReadModelApi(table)
    override val observe: ObserveModelApi<Model> = MockObserveModelApi(table)
}