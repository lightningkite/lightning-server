package com.lightningkite.ktordb

import kotlinx.coroutines.flow.Flow

interface WatchableFieldCollection<Model: Any> : FieldCollection<Model> {
    suspend fun watch(
        condition: Condition<Model>
    ): Flow<EntryChange<Model>>
}