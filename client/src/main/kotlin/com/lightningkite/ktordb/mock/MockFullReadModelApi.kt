@file:SharedCode
package com.lightningkite.ktordb.mock

import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.ktordb.*
import com.lightningkite.ktordb.HasId
import java.util.*

class MockFullReadModelApi<Model : HasId<UUID>>(
    val table: MockTable<Model>,
) : FullReadModelApi<Model>() {
    override val read: ReadModelApi<Model> = MockReadModelApi(table)
    override val observe: ObserveModelApi<Model> = MockObserveModelApi(table)
}