@file:SharedCode
package com.lightningkite.lightningdb.mock

import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.lightningdb.*
import com.lightningkite.lightningdb.HasId
import java.util.*

class MockCompleteModelApi<Model : HasId<UUID>>(
    val table: MockTable<Model>,
) : CompleteModelApi<Model>() {
    override val read: ReadModelApi<Model> = MockReadModelApi(table)
    override val write: WriteModelApi<Model> =
        MockWriteModelApi(table)
    override val observe: ObserveModelApi<Model> = MockObserveModelApi(table)
}