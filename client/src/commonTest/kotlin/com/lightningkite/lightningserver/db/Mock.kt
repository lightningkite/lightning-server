package com.lightningkite.lightningserver.db

import com.lightningkite.kiteui.*
import com.lightningkite.kiteui.reactive.Constant
import com.lightningkite.kiteui.reactive.Property
import com.lightningkite.kiteui.reactive.Readable
import com.lightningkite.lightningdb.*
import com.lightningkite.now
import kotlinx.datetime.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


class MockClientModelRestEndpoints<T : HasId<ID>, ID : Comparable<ID>>(val log: (String) -> Unit) :
    ClientModelRestEndpointsPlusUpdatesWebsocket<T, ID> {

    val hold = WaitGate(true)

    val items = HashMap<ID, T>()
    val watchers = ArrayList<(changes: List<EntryChange<T>>) -> Unit>()
    override suspend fun query(input: Query<T>): List<T> {
        log("query $input")
        hold.await()
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
        hold.await()
        TODO("Not yet implemented")
    }

    override suspend fun detail(id: ID): T {
        log("detail $id")
        hold.await()
        return items[id] ?: throw Exception("Not found")
    }

    override suspend fun insertBulk(input: List<T>): List<T> {
        log("insertBulk $input")
        hold.await()
        return input.onEach { items[it._id] = it }
            .also { watchers.forEach { w -> w(it.map { EntryChange(new = it) }) } }
    }

    override suspend fun insert(input: T): T {
        log("insert $input")
        hold.await()
        return input.also { items[it._id] = it }
            .also { watchers.forEach { w -> w(it.let { EntryChange(new = it) }.let(::listOf)) } }
    }

    override suspend fun upsert(id: ID, input: T): T {
        log("upsert $id $input")
        hold.await()
        val existing = items[id]
        return input.also { items[it._id] = it }
            .also { watchers.forEach { w -> w(it.let { EntryChange(old = existing, new = it) }.let(::listOf)) } }
    }

    override suspend fun bulkReplace(input: List<T>): List<T> {
        log("bulkReplace $input")
        hold.await()
        val existing = input.map { items[it._id]!! }
        return input.onEach { items[it._id] = it }
            .also {
                watchers.forEach { w -> w(existing.zip(it) { old, new -> EntryChange(old = old, new = new) }) }
            }
    }

    override suspend fun replace(id: ID, input: T): T {
        log("replace $id $input")
        hold.await()
        val existing = items[id]!!
        return input.also { items[it._id] = it }
            .also { watchers.forEach { w -> w(it.let { EntryChange(old = existing, new = it) }.let(::listOf)) } }
    }

    override suspend fun bulkModify(input: MassModification<T>): Int {
        log("bulkModify $input")
        hold.await()
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
        hold.await()
        val existing = items[id] ?: return EntryChange(null, null)
        val new = input(existing)
        items[id] = new
        watchers.forEach { w -> w(listOf(EntryChange(old = existing, new = new))) }
        return EntryChange(existing, new)
    }

    override suspend fun modify(id: ID, input: Modification<T>): T {
        log("modify $id $input")
        hold.await()
        val existing = items[id] ?: throw Exception()
        val new = input(existing)
        items[id] = new
        watchers.forEach { w -> w(listOf(EntryChange(old = existing, new = new))) }
        return new
    }

    override suspend fun bulkDelete(input: Condition<T>): Int {
        log("bulkDelete $input")
        hold.await()
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
        hold.await()
        val item = items.remove(id)
        item?.let { item ->
            watchers.forEach { it(listOf(EntryChange(old = item))) }
        }
    }

    override suspend fun count(input: Condition<T>): Int {
        log("count $input")
        hold.await()
        return items.values.count { input(it) }
    }

    override suspend fun groupCount(input: GroupCountQuery<T>): Map<String, Int> {
        log("groupCount $input")
        hold.await()
        return items.values.groupBy { input.groupBy.getAny(it).toString() }.mapValues { it.value.size }
    }

    override suspend fun aggregate(input: AggregateQuery<T>): Double? {
        log("aggregate $input")
        hold.await()
        TODO("Not yet implemented")
    }

    override suspend fun groupAggregate(input: GroupAggregateQuery<T>): Map<String, Double?> {
        log("groupAggregate $input")
        hold.await()
        TODO("Not yet implemented")
    }

    fun mockExternalChanges(list: List<EntryChange<T>>) {
        watchers.forEach { it(list) }
    }

    val holdWsConnect = WaitGate(true)
    val holdWsMessage = WaitGate(true)
    override fun updates(): TypedWebSocket<Condition<T>, CollectionUpdates<T, ID>> {
        return object : TypedWebSocket<Condition<T>, CollectionUpdates<T, ID>> {
            override val connected: Readable<Boolean> get() = Constant(true)
            override fun close(code: Short, reason: String) {}
            override fun onClose(action: (Short) -> Unit) {
            }

            override fun onOpen(action: () -> Unit) {
                launchGlobal {
                    log("updates CONNECTING")
                    holdWsConnect.await()
                    log("updates CONNECTED")
                    action()
                }
            }

            override fun send(data: Condition<T>) {
                lastCondition = data
                launchGlobal {
                    respond(CollectionUpdates(condition = data))
                }
            }

            suspend fun respond(data: CollectionUpdates<T, ID>) {
                log("updates <- $data waiting...")
                holdWsConnect.await()
                holdWsMessage.await()
                log("updates <- $data")
                onMessage.forEach { it(data) }
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
                launchGlobal {
                    respond(changes)
                }
                Unit
            }
            var count = 0
            override fun start(): () -> Unit {
                if (count++ == 0) {
                    log("updates START")
                    watchers.add(myListener)
                }
                return {
                    if (--count == 0) {
                        log("updates STOP")
                        watchers.remove(myListener)
                    }
                }
            }

        }
    }
}




class ConnectivityGate(val delay: suspend (ms: Long) -> Unit = { ms -> com.lightningkite.kiteui.delay(ms) }) {
    val gate = WaitGate(true)
    val baseRetry = 5.seconds
    var nextRetry = baseRetry
    val maxRetry = 5.minutes
    val retryAt = Property<Instant?>(null)

    fun retryNow() {
        retryAt.value = null
        gate.permit = true
    }

    fun abandon() {
        gate.abandon()
        retryAt.value = null
        gate.permit = true
    }

    suspend fun <T> run(tag: String, action: suspend () -> T): T {
        while (true) {
            gate.await()
            try {
                val r = action()
                nextRetry = baseRetry
                return r
            } catch (e: ConnectionException) {
                if (retryAt.value == null) {
                    launchGlobal {
                        val d = nextRetry
                        retryAt.value = now() + d
                        nextRetry = d.times(2).coerceAtMost(maxRetry)
                        gate.permit = false
                        delay(d.inWholeMilliseconds)
                        retryNow()
                    }
                }
            }
        }
    }
}

val noConnectivityCodes = setOf<Short>(502, 503, 420)
val connectivityFetchGate = ConnectivityGate()
suspend fun connectivityFetch(
    url: String,
    method: HttpMethod = HttpMethod.GET,
    headers: suspend () -> HttpHeaders = { httpHeaders() },
    body: RequestBody,
): RequestResponse {
    return if(coroutineContext[ConnectivityIssueSuppress.Key] == null) {
        connectivityFetchGate.run("$method $url") {
            val r = fetch(url = url, method = method, headers = headers(), body = body)
            if (r.status in noConnectivityCodes) throw ConnectionException("Status code ${r.status}")
            r
        }
    } else {
        fetch(url = url, method = method, headers = headers(), body = body)
    }
}

class ConnectivityIssueSuppress(): CoroutineContext.Element {
    override val key: CoroutineContext.Key<ConnectivityIssueSuppress> = Key
    object Key: CoroutineContext.Key<ConnectivityIssueSuppress>
}
