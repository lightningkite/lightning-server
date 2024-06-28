package com.lightningkite.lightningserver.db

import com.lightningkite.kiteui.*
import com.lightningkite.kiteui.reactive.*
import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.db.*
import com.lightningkite.now
import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class ModelCache<T : HasId<ID>, ID : Comparable<ID>>(
    override val skipCache: ClientModelRestEndpoints<T, ID>,
    val serializer: KSerializer<T>,
    val cacheTime: Duration = 5.minutes,
    val showReload: Boolean = false,
    val showReloadOnInvalidate: Boolean = false,
    val onUpdate: (T) -> Unit = {}
) : CachingModelRestEndpoints<T, ID> {
    var apiCalls: Int = 0
        private set
    val log = ConsoleRoot.tag("ModelCache3(${serializer.descriptor.serialName.substringAfterLast('.')})")
    var totalInvalidation: Instant = Instant.DISTANT_PAST
    override fun totallyInvalidate() {
        totalInvalidation = now()
    }

    private val idProp = serializer._id()

    //    private var desiredSocketCondition: Condition<T> = Condition.Never()
//    private var activeSocketCondition: Condition<T> = Condition.Never()
    private val itemCache = HashMap<ID, ItemHolder>()
    private val queryCache = HashMap<Query<T>, ListHolder>()
    private val itemWatchCache = HashMap<ID, WritableModel<T>>()
    private val queryWatchCache = HashMap<Query<T>, WatchingWrapper<ListHolder, List<T>>>()
    internal val sockets =
        (skipCache as? ClientModelRestEndpointsPlusUpdatesWebsocket)?.let {
            SharedChangeUpdateWrapper(it.updates()) {
                val u = it.updates.associateBy { it._id }
                it.updates.asSequence().map { itemHolder(it._id) }.plus(it.remove.map { itemHolder(it) })
                    .forEach {
                        it.onFreshData(u[it.id])
                    }
                flushLists()
            }
        }

    private fun itemHolder(id: ID): ItemHolder = itemCache.getOrPut(id) { ItemHolder(id) }
    private fun itemHolderWatch(id: ID): WritableModel<T> =
        sockets?.let { sockets ->
            itemWatchCache.getOrPut(id) {
                WatchingWrapperWritableModel(
                    itemHolder(id),
                    sockets.outsideResource(DataClassPathAccess(DataClassPathSelf(serializer), idProp).eq(id))
                )
            }
        } ?: itemHolder(id)

    private inner class ItemHolder(val id: ID) : WritableModel<T>, CacheReadable<T?>() {
        override val showReload: Boolean get() = this@ModelCache.showReload
        override val showReloadOnInvalidate: Boolean get() = this@ModelCache.showReloadOnInvalidate
        override val totalInvalidation: Instant get() = this@ModelCache.totalInvalidation
        override val cacheTime: Duration get() = this@ModelCache.cacheTime
        override val serializer: KSerializer<T> get() = this@ModelCache.serializer
        override fun addListener(listener: () -> Unit): () -> Unit {
            startLoop()
            return super.addListener(listener)
        }

        override suspend fun delete() {
            apiCalls++
            skipCache.delete(id)
            onFreshData(null)
            flushLists()
        }

        override suspend fun modify(modification: Modification<T>): T? {
            apiCalls++
            val value = skipCache.modify(id, modification)
            onFreshData(value)
            flushLists()
            return value
        }

        override suspend fun set(value: T?) {
            if (value == null) delete()
            else {
                apiCalls++
                onFreshData(skipCache.replace(id, value))
                flushLists()
            }
        }

        override fun onFreshData(value: T?) {
            queryCache.values.asSequence()
                .filter {
                    val matchesOld = lastKnownValue?.let { old -> it.query.condition(old) } ?: false
                    val matchesNew = value?.let { new -> it.query.condition(new) } ?: false
                    matchesNew || matchesOld
                }
                .forEach {
                    if (value != null) it.updating.queueItemUpdate(value)
                    else it.updating.delete(id)
                }
            super.onFreshData(value)
            value?.let { onUpdate(it) }
        }

        internal fun onFreshDataSkipQueries(value: T?) {
            super.onFreshData(value)
            value?.let { onUpdate(it) }
        }

        override fun toString(): String = "ItemHolder<${serializer.descriptor.serialName.substringAfterLast('.')}>($id)"
    }

    private fun listHolder(query: Query<T>): ListHolder = queryCache.getOrPut(query) { ListHolder(query) }
    private fun listHolderWatch(query: Query<T>): Readable<List<T>> =
        sockets?.let { sockets ->
            queryWatchCache.getOrPut(query) {
                WatchingWrapper(
                    listHolder(query),
                    sockets.outsideResource(query.condition)
                )
            }
        } ?: listHolder(query)

    private inner class ListHolder(val query: Query<T>) : CacheReadable<List<T>>() {
        override val showReload: Boolean get() = this@ModelCache.showReload
        override val showReloadOnInvalidate: Boolean get() = this@ModelCache.showReloadOnInvalidate
        override val totalInvalidation: Instant get() = this@ModelCache.totalInvalidation
        override val cacheTime: Duration get() = this@ModelCache.cacheTime
        override fun addListener(listener: () -> Unit): () -> Unit {
            startLoop()
            return super.addListener(listener)
        }

        val updating = UpdatingQueryList(query)

        override fun onFreshData(value: List<T>) {
            updating.fullPull(value)
            super.onFreshData(value)
        }
    }

    override fun get(id: ID): WritableModel<T> = itemHolder(id)
    override fun watch(id: ID): WritableModel<T> = itemHolderWatch(id)
    override suspend fun query(query: Query<T>): Readable<List<T>> = listHolder(query)
    override suspend fun watch(query: Query<T>): Readable<List<T>> = listHolderWatch(query)

    override suspend fun bulkModify(bulkUpdate: MassModification<T>): Int {
        apiCalls++
        val result = skipCache.bulkModify(bulkUpdate)
        totallyInvalidate()
        return result
    }

    override suspend fun insert(item: T): WritableModel<T> {
        apiCalls++
        return itemHolder(skipCache.insert(item))
    }

    override suspend fun insert(item: List<T>): List<T> {
        apiCalls++
        return skipCache.insertBulk(item).also {
            it.map { itemHolder(it._id).onFreshData(it) }
            flushLists()
        }
    }

    override suspend fun upsert(item: T): WritableModel<T> {
        apiCalls++
        return itemHolder(skipCache.upsert(item._id, item))
    }

    internal var allowLoop = true

    companion object {
        val universalLoop: ArrayList<()->Unit> by lazy {
            val listeners = ArrayList<()->Unit>()
            CalculationContext.NeverEnds.reactiveScope {
                if(InForeground()) {
                    while(true) {
                        delay(100)
                        listeners.invokeAllSafe()
                    }
                }
            }
            listeners
        }
    }

    private var isLooping = false
    internal fun startLoop() {
        if (!allowLoop) return
        if (isLooping) return
        var l: ()->Unit = {}
        var exceptionReported = false
        l = {
            try {
                regularly()
                val inUse = itemCache.values.any { it.inUse } || queryCache.values.any { it.inUse }
                if (!inUse) {
                    log.info("Stopped due to no one listening")
                    isLooping = false
                    universalLoop.remove(l)
                }
            } catch (e: Exception) {
                if (!exceptionReported) {
                    Exception(
                        "ModelCache3 ${serializer.descriptor.serialName.substringAfterLast('.')} loop failed",
                        e
                    ).report()
                    exceptionReported = true
                }
            }
        }
        log.info("Starting loop")
        universalLoop.add(l)
        isLooping = true
    }

    internal fun regularly() {
        launchGlobal {
            sockets?.flush()
        }
        queryCache.values.asSequence()
            .filter { it.shouldPull }
            .forEach {
                launchGlobal {
                    it.onLoadStart()
                    try {
                        apiCalls++
                        val data = skipCache.query(it.query)
                        data.forEach { itemHolder(it._id).onFreshDataSkipQueries(it) }
                        it.onFreshData(data)
                    } catch (e: Exception) {
                        it.onRetrievalError(e)
                    }
                }
            }
        val limit = 1000
        itemCache.values.asSequence()
            .filter { it.shouldPull }
            .chunked(limit)
            .toList()
            .filter { it.isNotEmpty() }
            .forEach {
                launchGlobal {
                    it.forEach { it.onLoadStart() }
                    try {
                        it.forEach { it.onLoadStart() }
                        apiCalls++
                        val values = skipCache.query(
                            Query(
                                condition = DataClassPathSelf(serializer).get(idProp).inside(it.map { it.id }),
                                limit = limit
                            )
                        ).associateBy { it._id }
                        it.forEach {
                            it.onFreshData(values[it.id])
                        }
                        flushLists()
                    } catch (e: Exception) {
                        it.forEach { it.onRetrievalError(e) }
                    }
                }
            }
    }

    private fun flushLists() {
        queryCache.values.forEach {
            it.updating.flush()?.let { l -> it.partialUpdate(l) }
        }
    }

    private fun itemHolder(item: T): ItemHolder = itemHolder(item._id).apply { onFreshData(item); flushLists() }

    fun localSignalUpdate(matching: (T) -> Boolean, modify: (T) -> T?) {
        itemCache.values
            .forEach {
                val v = it.lastKnownValue ?: return@forEach
                if (matching(v))
                    it.onFreshData(modify(v))
            }
        flushLists()
    }
}

class ChangeUpdateWrapper<T : HasId<ID>, ID : Comparable<ID>>(
    val sharedSocket: TypedWebSocket<Condition<T>, CollectionUpdates<T, ID>>,
    val onMessage: (CollectionUpdates<T, ID>) -> Unit
) {

    private var endUse: (() -> Unit)? = null
    var condition: Condition<T> = Condition.Never()
        set(value) {
            field = value
            if (value is Condition.Never) {
                endUse?.invoke()
                endUse = null
                messageList.forEach { it.resume(Unit) }
                messageList.clear()
            } else {
                if (endUse == null) {
                    endUse = sharedSocket.start()
                }
                if (sharedSocket.connected.state == ReadableState(true)) {
                    sharedSocket.send(value)
                }
            }
        }
    private val messageList = ArrayList<Continuation<Unit>>()

    suspend fun update(condition: Condition<T>): Boolean {
        suspendCoroutineCancellable { cont ->
            messageList.add(cont)
            this.condition = condition
            return@suspendCoroutineCancellable {
                messageList.remove(cont)
            }
        }
        return true
    }

    init {
        sharedSocket.onOpen {
            sharedSocket.send(condition)
        }
        sharedSocket.onClose {
        }
        sharedSocket.onMessage {
            if (it.condition == this.condition) {
                messageList.forEach { it.resume(Unit) }
                messageList.clear()
            } else if(it.condition != null) println("Ignoring condition update ${it.condition}; does not match ${this.condition}")
            onMessage(it)
        }
    }
}

class SharedChangeUpdateWrapper<T : HasId<ID>, ID : Comparable<ID>>(
    sharedSocket: TypedWebSocket<Condition<T>, CollectionUpdates<T, ID>>,
    onMessage: (CollectionUpdates<T, ID>) -> Unit
) {
    val wraps = ChangeUpdateWrapper(sharedSocket, onMessage)

    var queuedCondition: Condition<T>? = null
    fun refresh() {
        queuedCondition = if (conditionSet.isEmpty()) Condition.Never<T>() else Condition.Or(conditionSet.toList())
    }

    var awaitingSuccessfulFlush = ArrayList<Continuation<Unit>>()
    suspend fun refreshAndWait() {
        queuedCondition = if (conditionSet.isEmpty()) Condition.Never<T>() else Condition.Or(conditionSet.toList())
        suspendCoroutineCancellable<Unit> {
            awaitingSuccessfulFlush.add(it)
            return@suspendCoroutineCancellable { awaitingSuccessfulFlush.remove(it) }
        }
    }

    suspend fun flush() {
        queuedCondition?.let {
            queuedCondition = null
            val toComplete = awaitingSuccessfulFlush
            awaitingSuccessfulFlush = ArrayList()
            wraps.update(it)
            toComplete.forEach { it.resume(Unit) }
        }
    }

    var conditionSet = HashSet<Condition<T>>()
    fun outsideResource(condition: Condition<T>) = object : OutsideResource {
        override suspend fun start(): Boolean {
            conditionSet.add(condition)
            refreshAndWait()
            return true
        }

        override fun interruptStartup() = stop()

        override fun stop() {
            conditionSet.remove(condition)
            refresh()
        }
    }
}

interface OutsideResource {
    suspend fun start(): Boolean
    fun interruptStartup() = stop()
    fun stop()
}

class WatchingWrapperWritableModel<T, R>(base: R, outsideResource: OutsideResource) :
    WatchingWrapper<R, T?>(base, outsideResource), WritableModel<T> where R : WritableModel<T>, R : CacheReadable<T?> {
    override suspend fun set(value: T?) = base.set(value)
    override val serializer: KSerializer<T> get() = base.serializer
    override suspend fun delete() = base.delete()
    override fun invalidate() = (base as CacheReadable<T?>).invalidate()
    override suspend fun modify(modification: Modification<T>): T? = base.modify(modification)

}

open class WatchingWrapper<R : CacheReadable<T>, T>(val base: R, val outsideResource: OutsideResource) : Readable<T> {
    private var uses = 0
    override val state: ReadableState<T> get() = base.state
    private var starting = false
    private var started = false
    override fun addListener(listener: () -> Unit): () -> Unit {
        uses++
        val b = base.addListener { listener() }
        if (!starting && !started) {
            starting = true
            base.establishingSocket = true
            launchGlobal {
                try {
                    started = outsideResource.start()
                    if (started) base.socketIsLive = true
                } finally {
                    base.establishingSocket = false
                    starting = false
                }
            }
        }
        return {
            b()
            --uses
            if (uses == 0) {
                if (started) {
                    started = false
                    outsideResource.stop()
                } else {
                    outsideResource.interruptStartup()
                }
            }
        }
    }
}

abstract class CacheReadable<T> : BaseReadable2<T>() {
    abstract val cacheTime: Duration
    abstract val totalInvalidation: Instant
    open val showReload: Boolean get() = true
    open val showReloadOnInvalidate: Boolean get() = true

    private var freshDataReceivedAt: Instant = Instant.DISTANT_PAST
    private var socketLiveSince: Instant = Instant.DISTANT_FUTURE

    private inline val totalInvalidationRequired: Boolean get() = freshDataReceivedAt < totalInvalidation
    private inline val freshWithinCacheTime: Boolean get() = now() < freshDataReceivedAt + cacheTime
    private inline val wouldHaveSeenChanges: Boolean get() = socketLiveSince < freshDataReceivedAt

    fun invalidate() {
        freshDataReceivedAt = Instant.DISTANT_PAST
        if(showReloadOnInvalidate) state = ReadableState.notReady
    }

    val upToDate: Boolean
        get() = (!totalInvalidationRequired && (freshWithinCacheTime || wouldHaveSeenChanges)).also {
            if ((!it && showReload) || (totalInvalidationRequired && showReloadOnInvalidate)) state = ReadableState.notReady
        }
    val shouldPull: Boolean
        get() {
            return !establishingSocket && !upToDate && inUse && !requestOpen
        }

    var establishingSocket: Boolean = false
    var socketIsLive: Boolean = false
        set(value) {
            if (!field && value) socketLiveSince = now()
            else if (field && !value) socketLiveSince = Instant.DISTANT_FUTURE
            field = value
        }

    var requestOpen: Boolean = false
        private set

    var lastKnownValue: T? = null

    fun onLoadStart() {
        requestOpen = true
        if(showReload) state = ReadableState.notReady
    }

    fun partialUpdate(value: T) {
        if (upToDate) {
            state = ReadableState(value)
            lastKnownValue = value
        }
    }

    open fun onFreshData(value: T) {
        requestOpen = false
        freshDataReceivedAt = now()
        state = ReadableState(value)
        lastKnownValue = value
    }

    fun onRetrievalError(exception: Exception) {
        requestOpen = false
        freshDataReceivedAt = now()
        state = ReadableState.exception(exception)
    }
}

abstract class BaseReadable2<T>(start: ReadableState<T> = ReadableState.notReady) : Readable<T> {
    private val listeners = ArrayList<() -> Unit>()
    internal val inUse: Boolean get() = listeners.isNotEmpty()
    override var state: ReadableState<T> = start
        protected set(value) {
            if (field != value) {
                field = value
                listeners.invokeAllSafe()
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
}

class UpdatingQueryList<T : HasId<ID>, ID : Comparable<ID>>(val query: Query<T>) {
    val comparator = query.orderBy.comparator?.thenBy { it._id } ?: compareBy { it._id }
    val queued = ArrayList<T>()
    var updatesMade: Boolean = false
    fun delete(id: ID) {
        updatesMade = queued.removeAll { it._id == id }
    }

    var total: Boolean = false
    fun fullPull(list: List<T>) {
        queued.clear()
        queued.addAll(list.sortedWith(comparator))
        total = list.size < query.limit
        updatesMade = true
    }

    fun queueItemUpdate(item: T) {
        val afterEnd = queued.lastOrNull()?.let { comparator.compare(item, it) > 0 } ?: false
        var itemFound = false
        var itemReplaced = !query.condition(item)
        var index = 0
        while (!(itemReplaced && itemFound) && index < queued.size) {
            val found = queued[index]
            if (!itemFound && found._id == item._id) {
                queued.removeAt(index)
                updatesMade = true
                itemFound = true
                continue
            }
            if (!itemReplaced && comparator.compare(item, found) < 0) {
                queued.add(index, item)
                updatesMade = true
                itemReplaced = true
                index++
            }
            index++
        }
        if (!itemReplaced && (total || !afterEnd)) {
            updatesMade = true
            queued.add(item)
        }
    }

    fun flush(): List<T>? {
        if (updatesMade) {
            updatesMade = false
            return queued.toList()
        }
        return null
    }
}
