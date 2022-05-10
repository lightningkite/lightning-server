package com.lightningkite.ktordb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

class WatchableSecuredFieldCollection<Model: Any>(
    override val wraps: WatchableFieldCollection<Model>,
    rules: SecurityRules<Model>,
): SecuredFieldCollection<Model>(wraps, rules), WatchableFieldCollection<Model> {
    override suspend fun watch(
        condition: Condition<Model>
    ): Flow<EntryChange<Model>> = wraps.watch(condition and rules.read(condition))
        .mapNotNull {
            val old = it.old?.let { rules.mask(it) }
            val new = it.new?.let { rules.mask(it) }
            if(old == new) null
            else EntryChange(old, new)
        }
}