package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.kiteui.TypedWebSocket
import com.lightningkite.kiteui.launchGlobal
import com.lightningkite.kiteui.reactive.Constant
import com.lightningkite.kiteui.reactive.Readable

class MockModelRestEndpoints<T : HasId<ID>, ID : Comparable<ID>>(val log: (String) -> Unit) :
    ModelRestEndpointsPlusWs<T, ID>, ModelRestEndpointsPlusUpdatesWebsocket<T, ID> {
    val items = HashMap<ID, T>()
    val watchers = ArrayList<(changes: List<EntryChange<T>>) -> Unit>()
    override suspend fun query(input: Query<T>): List<T> {
        log("query $input")
        return items.values.asSequence()
            .filter { input.condition(it) }
            .let {
                input.orderBy.comparator?.let { c ->
                    it.sortedWith(c)
                } ?: it
            }
            .drop(input.skip)
            .take(input.limit)
            .toList()
    }

    override suspend fun queryPartial(input: QueryPartial<T>): List<Partial<T>> {
        log("queryPartial $input")
        TODO("Not yet implemented")
    }

    override suspend fun detail(id: ID): T {
        log("detail $id")
        return items[id] ?: throw Exception("Not found")
    }

    override suspend fun insertBulk(input: List<T>): List<T> {
        log("insertBulk $input")
        return input.onEach { items[it._id] = it }
            .also { watchers.forEach { w -> w(it.map { EntryChange(new = it) }) } }
    }

    override suspend fun insert(input: T): T {
        log("insert $input")
        return input.also { items[it._id] = it }
            .also { watchers.forEach { w -> w(it.let { EntryChange(new = it) }.let(::listOf)) } }
    }

    override suspend fun upsert(id: ID, input: T): T {
        log("upsert $id $input")
        val existing = items[id]
        return input.also { items[it._id] = it }
            .also { watchers.forEach { w -> w(it.let { EntryChange(old = existing, new = it) }.let(::listOf)) } }
    }

    override suspend fun bulkReplace(input: List<T>): List<T> {
        log("bulkReplace $input")
        val existing = input.map { items[it._id]!! }
        return input.onEach { items[it._id] = it }
            .also {
                watchers.forEach { w -> w(existing.zip(it) { old, new -> EntryChange(old = old, new = new) }) }
            }
    }

    override suspend fun replace(id: ID, input: T): T {
        log("replace $id $input")
        val existing = items[id]!!
        return input.also { items[it._id] = it }
            .also { watchers.forEach { w -> w(it.let { EntryChange(old = existing, new = it) }.let(::listOf)) } }
    }

    override suspend fun bulkModify(input: MassModification<T>): Int {
        log("bulkModify $input")
        val changes = ArrayList<EntryChange<T>>()
        for ((key, existing) in items) {
            if (input.condition(existing)) {
                val new = input.modification(existing)
                items[key] = new
                changes.add(EntryChange(old = existing, new = new))
            }
        }
        watchers.forEach { w -> w(changes) }
        return changes.size
    }

    override suspend fun modifyWithDiff(id: ID, input: Modification<T>): EntryChange<T> {
        log("modifyWithDiff $id $input")
        val existing = items[id] ?: return EntryChange(null, null)
        val new = input(existing)
        items[id] = new
        watchers.forEach { w -> w(listOf(EntryChange(old = existing, new = new))) }
        return EntryChange(existing, new)
    }

    override suspend fun modify(id: ID, input: Modification<T>): T {
        log("modify $id $input")
        val existing = items[id] ?: throw Exception()
        val new = input(existing)
        items[id] = new
        watchers.forEach { w -> w(listOf(EntryChange(old = existing, new = new))) }
        return new
    }

    override suspend fun bulkDelete(input: Condition<T>): Int {
        log("bulkDelete $input")
        val iter = items.iterator()
        val removed = ArrayList<EntryChange<T>>()
        while (iter.hasNext()) {
            val current = iter.next()
            if (input(current.value)) {
                removed.add(EntryChange(old = current.value))
                iter.remove()
            }
        }
        watchers.forEach { w -> w(removed) }
        return removed.size
    }

    override suspend fun delete(id: ID) {
        log("delete $id")
        val item = items.remove(id)
        item?.let { item ->
            watchers.forEach { it(listOf(EntryChange(old = item))) }
        }
    }

    override suspend fun count(input: Condition<T>): Int {
        log("count $input")
        return items.values.count { input(it) }
    }

    override suspend fun groupCount(input: GroupCountQuery<T>): Map<String, Int> {
        log("groupCount $input")
        return items.values.groupBy { input.groupBy.getAny(it).toString() }.mapValues { it.value.size }
    }

    override suspend fun aggregate(input: AggregateQuery<T>): Double? {
        log("aggregate $input")
        TODO("Not yet implemented")
    }

    override suspend fun groupAggregate(input: GroupAggregateQuery<T>): Map<String, Double?> {
        log("groupAggregate $input")
        TODO("Not yet implemented")
    }

    fun mockExternalChanges(list: List<EntryChange<T>>) {
        watchers.forEach { it(list) }
    }

    override suspend fun watch(): TypedWebSocket<Query<T>, ListChange<T>> {
        return object : TypedWebSocket<Query<T>, ListChange<T>> {
            override val connected: Readable<Boolean> get() = Constant(true)
            override fun close(code: Short, reason: String) {}
            override fun onClose(action: (Short) -> Unit) {
            }

            override fun onOpen(action: () -> Unit) {
                action()
            }

            override fun send(data: Query<T>) {
                lastQuery = data
                if(data.limit > 0) {
                    launchGlobal {
                        val result = query(data)
                        onMessage.toList().forEach { it(ListChange(wholeList = result)) }
                    }
                }
            }

            var lastQuery: Query<T> = Query(condition = Condition.Never())
            val onMessage = ArrayList<(ListChange<T>) -> Unit>()
            override fun onMessage(action: (ListChange<T>) -> Unit) {
                onMessage.add(action)
            }

            val myListener = { list: List<EntryChange<T>> ->
                list
                    .mapNotNull {
                        ListChange(
                            old = it.old?.takeIf { lastQuery.condition(it) },
                            new = it.new?.takeIf { lastQuery.condition(it) },
                        ).takeUnless { it.old == null && it.new == null }
                    }
                    .forEach { onMessage.forEach { l -> l(it) } }
                Unit
            }
            var count = 0
            override fun start(): () -> Unit {
                if (count++ == 0) {
                    watchers.add(myListener)
                }
                return {
                    if (--count == 0) {
                        watchers.remove(myListener)
                    }
                }
            }

        }
    }

    override suspend fun updates(): TypedWebSocket<Condition<T>, CollectionUpdates<T, ID>> {
        return object : TypedWebSocket<Condition<T>, CollectionUpdates<T, ID>> {
            override val connected: Readable<Boolean> get() = Constant(true)
            override fun close(code: Short, reason: String) {}
            override fun onClose(action: (Short) -> Unit) {
            }

            override fun onOpen(action: () -> Unit) {
                action()
            }

            override fun send(data: Condition<T>) {
                lastCondition = data
            }

            var lastCondition: Condition<T> = Condition.Never()
            val onMessage = ArrayList<(CollectionUpdates<T, ID>) -> Unit>()
            override fun onMessage(action: (CollectionUpdates<T, ID>) -> Unit) {
                onMessage.add(action)
            }

            val myListener = { list: List<EntryChange<T>> ->
                val changes = CollectionUpdates<T, ID>(
                    updates = list.mapNotNull { it.new }.toSet(),
                    remove = list.mapNotNull { it.old.takeIf { _ -> it.new == null }?._id }.toSet()
                )
                onMessage.forEach { it(changes) }
                Unit
            }
            var count = 0
            override fun start(): () -> Unit {
                if (count++ == 0) {
                    watchers.add(myListener)
                }
                return {
                    if (--count == 0) {
                        watchers.remove(myListener)
                    }
                }
            }

        }
    }
}