package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.kiteui.*
import com.lightningkite.kiteui.reactive.Readable
import com.lightningkite.kiteui.reactive.ReadableState
import com.lightningkite.kiteui.reactive.await
import kotlinx.serialization.KSerializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class ModelCache<T : HasId<ID>, ID : Comparable<ID>>(
    override val skipCache: ModelRestEndpoints<T, ID>,
    val serializer: KSerializer<T>,
    cacheTime: Duration = 5.minutes,
) : CachingModelRestEndpoints<T, ID> {
    var live: Boolean = false
    val cacheMs = cacheTime.inWholeMilliseconds.toDouble()
    val idProp = serializer._id()

    inner class WritableModelImpl(val id: ID) : WritableModel<T> {
        var live: Int = 0
        var lastSet: Double = 0.0
        var invalid = false // The invalid logic and code isn't the cleanest, not entirely what I want, but it works.
        var ready: Boolean = false

        override val serializer: KSerializer<T>
            get() = this@ModelCache.serializer
        private val listeners = ArrayList<() -> Unit>()

        val inUse: Boolean get() = listeners.isNotEmpty()
        val upToDate: Boolean get() = ready && (live > 0 || clockMillis() - lastSet < cacheMs)

        var value: T? = null
            set(value) {
                ready = true
                lastSet = clockMillis()
                invalid = false
                field = value
                listeners.toList().forEach {
                    try {
                        it()
                    } catch(e: Exception) {
                        e.printStackTrace2()
                    }
                }
            }

        override fun addListener(listener: () -> Unit): () -> Unit {
            listeners.add(listener)
            return {
                val pos = listeners.indexOfFirst { it === listener }
                if (pos != -1) {
                    listeners.removeAt(pos)
                }
            }
        }

        override val state: ReadableState<T?>
            get() = if(upToDate) ReadableState(value) else ReadableState.notReady

        override suspend infix fun set(value: T?) {
            if (value == null) delete()
            else {
                val result = skipCache.replace(id, value)
                this.value = result
                for (query in queries) {
                    query.value.onNewValue(result)
                    query.value.refreshIfNeeded()
                }
            }
        }

        override suspend fun modify(modification: Modification<T>): T? {
            val result = try {
                skipCache.modify(id, modification)
            } catch (e: Exception) {
                null
            }  // TODO: we can do better than this
//            val oldValue = value
            value = result
            for (query in queries) {
//                oldValue?._id?.let { query.value.onRemoved(it) }
                result?.let { query.value.onNewValue(it) }
                query.value.refreshIfNeeded()
            }
            return result
        }

        override suspend fun delete() {
            skipCache.delete(id)
            virtualDelete()
        }

        override fun invalidate() {
            invalid = true
        }

        fun virtualDelete() {
            val oldValue = value
//            println("Assigned value to null")
            value = null
            oldValue?.let { value ->
                for (query in queries) {
                    query.value.onRemoved(id)
                    query.value.refreshIfNeeded()
                }
            }
        }
    }

    private var totalInvalidation: Double = 0.0

    override fun totallyInvalidate(){
        totalInvalidation = clockMillis()
        cache.values.forEach { it.invalidate() }
    }

    inner class ListImpl(val query: Query<T>) : Readable<List<T>> {
        var live: Int = 0
        var lastSet: Double = 0.0
        var lastInvalidateCheck = clockMillis()
        val upToDate: Boolean
            get() {
                val now = clockMillis()
                val result =
                    (ready && (live > 0 || (now - lastSet < cacheMs && lastInvalidateCheck > totalInvalidation)))
                lastInvalidateCheck = now
                return result
            }
        val comparator = query.orderBy.comparator ?: compareBy { it._id }

        var hasMorePages: Boolean = false
        var ready: Boolean = false
        val ids = ArrayList<ID>()
        var unreportedChanges = false
        fun refreshIfNeeded() {
            if (unreportedChanges) {
                unreportedChanges = false
                listeners.toList().forEach { it() }
            }
        }

        private val listeners = ArrayList<() -> Unit>()
        val inUse: Boolean get() = listeners.isNotEmpty()
        override fun addListener(listener: () -> Unit): () -> Unit {
            listeners.add(listener)
            return {
                val pos = listeners.indexOfFirst { it === listener }
                if (pos != -1) {
                    listeners.removeAt(pos)
                    unreportedChanges = true
                }
            }
        }

        fun onNewValue(item: T) {
            if (!query.condition(item)) {
                unreportedChanges = unreportedChanges || ids.remove(item._id)
                return
            }
            if (hasMorePages) {
                val lastItem = cache[ids.lastOrNull() ?: return]?.value ?: return
                if (comparator.compare(item, lastItem) > 0) return
            }
            if (item._id in ids) {
                unreportedChanges = true
                return
            }
            for (index in ids.indices) {
                val nextItem = cache[ids.getOrNull(index) ?: continue]?.value ?: continue
                if (comparator.compare(item, nextItem) < 0) {
                    ids.add(index, item._id)
                    unreportedChanges = true
                    return
                }
            }
            ids.add(item._id)
            unreportedChanges = true
        }

        fun onRemoved(id: ID) {
            unreportedChanges = unreportedChanges || ids.remove(id)
        }

        fun reset(ids: Collection<ID>) {
            ready = true
            lastSet = clockMillis()
            this.ids.clear()
            this.ids.addAll(ids)
            hasMorePages = ids.size == query.limit
            unreportedChanges = true
        }

        override val state: ReadableState<List<T>>
            get() = if (ready) ReadableState(ids.mapNotNull { cache[it]?.value }) else ReadableState.notReady
    }

    val cache = HashMap<ID, WritableModelImpl>()
    val queries = HashMap<Query<T>, ListImpl>()

    private interface LivingSocket<T> {
        fun start(): () -> Unit
        val connected: Readable<Boolean>
        fun send(condition: Condition<T>)
    }

    private val currentSocket: Async<LivingSocket<T>?> = asyncGlobal {
        (skipCache as? ModelRestEndpointsPlusUpdatesWebsocket<T, ID>)?.updates()?.apply {
            onMessage {
                it.updates.forEach { new ->
                    cache.getOrPut(new._id) { WritableModelImpl(new._id) }.apply {
                        value = new
                    }
                    queries.forEach { it.value.onNewValue(new); it.value.refreshIfNeeded() }
                }
                it.remove.forEach { old ->
                    cache[old]?.virtualDelete()
                }
            }
            onOpen {
                send(socketCondition)
            }
        }?.let {
            object : LivingSocket<T> {
                override fun start(): () -> Unit = it.start()
                override val connected: Readable<Boolean> get() = it.connected
                override fun send(condition: Condition<T>) = it.send(condition)
            }
        } ?: (skipCache as? ModelRestEndpointsPlusWs<T, ID>)?.watch()?.apply {
            onMessage {
                val old = it.old
                val new = it.new
                when {
                    new != null -> {
                        cache.getOrPut(new._id) { WritableModelImpl(new._id) }.apply {
                            value = new
                        }
                        queries.forEach { it.value.onNewValue(new); it.value.refreshIfNeeded() }
                    }

                    old != null -> {
                        cache[old._id]?.virtualDelete()
                    }
                }
            }
            onOpen {
                send(Query(socketCondition, limit = 0))
            }
        }?.let {
            object : LivingSocket<T> {
                override fun start(): () -> Unit = it.start()
                override val connected: Readable<Boolean> get() = it.connected
                override fun send(condition: Condition<T>) = it.send(Query(condition, limit = 0))
            }
        }
    }
    private var socketCondition: Condition<T> = Condition.Never()
    private var socketEnder: (() -> Unit)? = null
    suspend fun updateSocket(condition: Condition<T>) {
        socketCondition = condition
        val socket = currentSocket.await() ?: return
        if (socket.connected.await()) {
            socket.send(condition)
        }
        if (condition is Condition.Never) {
            if (socketEnder != null) {
                socketEnder?.invoke()
                socketEnder = null
            }
        } else {
            if (socketEnder == null) {
                socketEnder = socket.start()
            }
        }
    }

    var listeningDirty = false

    override fun get(id: ID): WritableModel<T> = cache.getOrPut(id) { WritableModelImpl(id) }

    override fun watch(id: ID): WritableModel<T> {
        val original = cache.getOrPut(id) { WritableModelImpl(id) }
        return object : WritableModel<T> by original {
            override fun addListener(listener: () -> Unit): () -> Unit {
                val o = original.addListener(listener)
                var once = true
                if (original.live++ == 0) listeningDirty = true
                return {
                    if (once) {
                        once = false
                        if (--original.live == 0) listeningDirty = true
                    }
                    o()
                }
            }
        }
    }

    override suspend fun query(query: Query<T>): Readable<List<T>> = queries.getOrPut(query) {
        ListImpl(query)
    }

    override suspend fun watch(query: Query<T>): Readable<List<T>> {
        val original = queries.getOrPut(query) {
            ListImpl(query)
        }
        return object : Readable<List<T>> by original {
            override fun addListener(listener: () -> Unit): () -> Unit {
                val o = original.addListener(listener)
                var once = true
                if (original.live++ == 0) listeningDirty = true
                return {
                    if (once) {
                        once = false
                        if (--original.live == 0) listeningDirty = true
                    }
                    o()
                }
            }
        }
    }

    override suspend fun insert(item: T): WritableModel<T> {
        val new = skipCache.insert(item)
        val id = new._id
        val impl = cache.getOrPut(id) { WritableModelImpl(id) }
        impl.value = new
        queries.forEach { it.value.onNewValue(new); it.value.refreshIfNeeded() }
        return impl
    }

    override suspend fun insert(item: List<T>): List<T> {
        val newItems = skipCache.insertBulk(item)
        newItems.forEach {
            val new = skipCache.insert(it)
            val id = new._id
            val impl = cache.getOrPut(id) { WritableModelImpl(id) }
            impl.value = new
        }
        queries.forEach {
            for (item in newItems) it.value.onNewValue(item)
            it.value.refreshIfNeeded()
        }
        return newItems
    }

    override suspend fun upsert(item: T): WritableModel<T> {
        val new = skipCache.upsert(item._id, item)
        val impl = cache.getOrPut(new._id) { WritableModelImpl(new._id) }
        impl.value = new
        queries.forEach {
            it.value.onNewValue(new)
            it.value.refreshIfNeeded()
        }
        return impl
    }

    override suspend fun bulkModify(bulkUpdate: MassModification<T>): Int {
        totallyInvalidate()
        return skipCache.bulkModify(bulkUpdate)
    }

    var locked = false
    suspend fun regularly() {
        if(locked) return
        locked = true
        try {
            if (listeningDirty) {
                listeningDirty = false
                val subConditions = queries.values.mapNotNull { if (it.live == 0) null else it.query.condition } +
                        cache.values.mapNotNull { if (it.live == 0) null else it.id }
                            .takeUnless { it.isEmpty() }
                            ?.let { Condition.OnField(idProp, Condition.Inside(it)) }
                            .let(::listOfNotNull)
                updateSocket(if (subConditions.isEmpty()) Condition.Never() else Condition.Or(subConditions))
            }
            for (query in queries.values.toList()) {
                if (query.inUse && !query.upToDate) {
                    skipCache.query(query.query).let {
                        for (item in it) cache.getOrPut(item._id) { WritableModelImpl(item._id) }.value = item
                        query.reset(it.map { it._id })
                    }
                }
            }

            val needsUpdates =
                cache.values.toList().asSequence().filter { (it.inUse && !it.upToDate) || it.invalid }.map { it.id }
                    .toSet()
            if (needsUpdates.isNotEmpty()) {
                val limit = 1000
                val results = needsUpdates
                    .chunked(limit)
                    .flatMap {
                        skipCache.query(
                            Query(
                                DataClassPathSelf(serializer).get(idProp).inside(needsUpdates),
                                limit = limit
                            )
                        )
                    }
                for (id in needsUpdates) {
//                    println("Need update for ${serializer.descriptor.serialName} $id")
                    results.find { it._id == id }?.also { updated ->
//                        println("Found updated ${updated}")
                        cache.getOrPut(id) { WritableModelImpl(id) }.value = updated
                        for (query in queries.values.toList()) {
                            query.onNewValue(updated)
                        }
                    } ?: run {
//                        println("Did not find it; virtually deleting")
                        cache[id]?.virtualDelete()
                    }
                }
            }

            for (query in queries.values.toList()) {
                query.refreshIfNeeded()
            }
        } finally {
            locked = false
        }
    }

    init {
        launchGlobal {
            while (true) {
                delay(200)
                regularly()
            }
        }
    }
}