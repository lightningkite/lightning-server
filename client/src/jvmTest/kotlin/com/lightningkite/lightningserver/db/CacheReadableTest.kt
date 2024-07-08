package com.lightningkite.lightningserver.db

import com.lightningkite.kiteui.TypedWebSocket
import com.lightningkite.kiteui.launch
import com.lightningkite.kiteui.launchGlobal
import com.lightningkite.kiteui.reactive.*
import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.db.ClientModelRestEndpoints
import com.lightningkite.lightningserver.db.prepareModels
import com.lightningkite.now
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ModelCache3Test() {
    init {
        prepareModels()
    }
    @Test fun fromScratch() {
        val r = MockClientModelRestEndpoints<Item, Int>(::println)
        val cache = ModelCache(r, Item.serializer())
        cache.allowLoop = false

        testContext {
            var c: List<Item> = listOf()
            reactiveScope { c = cache.watch(Query())() }

            launch {
                repeat(100) {
                    val x = Item(it)
                    cache.insert(x)
                    cache.regularly()
                }
            }
            assertEquals(100, c.size)
        }
    }
    @Test fun basicsLocal() {
        val r = MockClientModelRestEndpoints<Item, Int>(::println)
        val cache = ModelCache(r, Item.serializer())
        cache.allowLoop = false

        testContext {
            var a: Item? = null
            reactiveScope { a = cache.watch(1)() }
            var b: Item? = null
            reactiveScope { b = cache.get(1)() }
            var c: List<Item> = listOf()
            reactiveScope { c = cache.watch(Query())() }
            var d: List<Item> = listOf()
            reactiveScope { d = cache.query(Query())() }

            launch {
                val x = Item(1)
                cache.insert(x)
                cache.regularly()

                assertEquals(x, a)
                assertEquals(x, b)
                assertEquals(listOf(x), c)
                assertEquals(listOf(x), d)

                var y = Item(2)
                cache.insert(y)
                cache.regularly()

                assertEquals(listOf(x, y), c)
                assertEquals(listOf(x, y), d)

                cache[1].delete()
                cache.regularly()
                assertEquals(null, a)
                assertEquals(null, b)
                assertEquals(listOf(y), c)
                assertEquals(listOf(y), d)

                cache[2].modify(modification { it.creation += 1 })
                y = y.copy(creation = y.creation + 1)
                assertEquals(listOf(y), c)
                assertEquals(listOf(y), d)
            }
            println("Complete")
        }
    }
    @Test fun followUpQuery() {
        val r = MockClientModelRestEndpoints<Item, Int>(::println)
        val cache = ModelCache(r, Item.serializer())
        cache.allowLoop = false

        testContext {
            var c: List<Item> = listOf()
            reactiveScope { c = cache.query(Query())() }

            launch {
                val x = Item(1)
                cache.insert(x)
                cache.regularly()

                assertEquals(listOf(x), c)

                var y = Item(2)
                cache.insert(y)
                cache.regularly()

                assertEquals(listOf(x, y), c)

                var d: List<Item> = listOf()
                reactiveScope { d = cache.query(Query(condition = condition { it._id lt 10 }))() }
                cache.regularly()
                assertEquals(listOf(x, y), d)

                cache[1].delete()
                cache.regularly()
                assertEquals(listOf(y), c)
                assertEquals(listOf(y), d)

                cache[2].modify(modification { it.creation += 1 })
                y = y.copy(creation = y.creation + 1)
                assertEquals(listOf(y), c)
                assertEquals(listOf(y), d)
            }
            println("Complete")
        }
    }
    @Test fun onceQuery() {
        val r = MockClientModelRestEndpoints<Item, Int>(::println)
        val cache = ModelCache(r, Item.serializer())
        cache.allowLoop = false

        testContext {
            var c: List<Item> = listOf()
            reactiveScope { c = cache.query(Query())() }

            launch {
                val x = Item(1)
                cache.insert(x)
                cache.regularly()

                assertEquals(listOf(x), c)

                var y = Item(2)
                cache.insert(y)
                cache.regularly()

                assertEquals(listOf(x, y), c)

                var d: List<Item> = listOf()
                launch { d = cache.query(Query(condition = condition { it._id lt 10 }))() }
                cache.regularly()
                println("d2: $d")
                assertEquals(listOf(x, y), d)

                cache[1].delete()
                cache.regularly()
                assertEquals(listOf(y), c)

                cache[2].modify(modification { it.creation += 1 })
                y = y.copy(creation = y.creation + 1)
                assertEquals(listOf(y), c)
            }
            println("Complete")
        }
    }
    @Test fun basicsLocalNoWs() {
        val r = MockClientModelRestEndpoints<Item, Int>(::println)
        val cache = ModelCache(object: ClientModelRestEndpoints<Item, Int> by r {}, Item.serializer())
        cache.allowLoop = false

        testContext {
            var a: Item? = null
            reactiveScope { a = cache.watch(1)() }
            var b: Item? = null
            reactiveScope { b = cache.get(1)() }
            var c: List<Item> = listOf()
            reactiveScope { c = cache.watch(Query())() }
            var d: List<Item> = listOf()
            reactiveScope { d = cache.query(Query())() }

            launch {
                val x = Item(1)
                cache.insert(x)
                cache.regularly()

                assertEquals(x, a)
                assertEquals(x, b)
                assertEquals(listOf(x), c)
                assertEquals(listOf(x), d)

                var y = Item(2)
                cache.insert(y)
                cache.regularly()

                assertEquals(listOf(x, y), c)
                assertEquals(listOf(x, y), d)

                cache[1].delete()
                cache.regularly()
                assertEquals(null, a)
                assertEquals(null, b)
                assertEquals(listOf(y), c)
                assertEquals(listOf(y), d)

                cache[2].modify(modification { it.creation += 1 })
                y = y.copy(creation = y.creation + 1)
                assertEquals(listOf(y), c)
                assertEquals(listOf(y), d)
            }
            println("Complete")
        }
    }
    @Test fun basicsRemote() {
        val r = MockClientModelRestEndpoints<Item, Int>(::println)
        val cache = ModelCache(r, Item.serializer())
        cache.allowLoop = false

        testContext {
            var a: Item? = null
            reactiveScope { a = cache.watch(1)() }
            var b: Item? = null
            reactiveScope { b = cache.get(1)() }
            var c: List<Item> = listOf()
            reactiveScope { c = cache.watch(Query())() }
            var d: List<Item> = listOf()
            reactiveScope { d = cache.query(Query())() }

            launch {
                val x = Item(1)
                cache.skipCache.insert(x)
                cache.regularly()

                assertEquals(x, a)
                assertEquals(x, b)
                assertEquals(listOf(x), c)
                assertEquals(listOf(x), d)

                var y = Item(2)
                cache.skipCache.insert(y)
                cache.regularly()

                assertEquals(listOf(x, y), c)
                assertEquals(listOf(x, y), d)

                cache.skipCache.delete(1)
                cache.regularly()
                assertEquals(null, a)
                assertEquals(null, b)
                assertEquals(listOf(y), c)
                assertEquals(listOf(y), d)

                cache.skipCache.modify(2, modification { it.creation += 1 })
                y = y.copy(creation = y.creation + 1)
                assertEquals(listOf(y), c)
                assertEquals(listOf(y), d)
            }
            println("Complete")
        }
    }
    @Test fun websocket() {
        val r = MockClientModelRestEndpoints<Item, Int>(::println)
        val c = ModelCache(r, Item.serializer())

        testContext {
            reactiveScope { println("c.watch(1): ${c.watch(1)()}") }
            launch {
                r.insert(Item(1))
                println("Insert finished")
                c.regularly()
            }
            println("Complete")
        }
    }

    @Test fun regularPull() = timeTravelTest { clock ->
        val r = MockClientModelRestEndpoints<Item, Int>(::println)
        val cache = ModelCache(object: ClientModelRestEndpoints<Item, Int> by r {}, Item.serializer())
        cache.allowLoop = false

        testContext {
            var itemsList: List<Item> = listOf()
            reactiveScope { itemsList = cache.watch(Query())().also { println(it) } }
            launch {
                cache.regularly()
                val x = Item(1)
                cache.skipCache.insert(x)
                cache.regularly()
                assertEquals(listOf(), itemsList)

                clock += cache.cacheTime - 1.seconds
                cache.regularly()
                assertEquals(listOf(), itemsList)

                clock += 2.seconds
                cache.regularly()
                assertEquals(listOf(x), itemsList)
            }
        }
    }

    @Test fun invalidationWorks() = timeTravelTest { clock ->
        val r = MockClientModelRestEndpoints<Item, Int>(::println)
        val cache = ModelCache(object: ClientModelRestEndpoints<Item, Int> by r {}, Item.serializer())
        cache.allowLoop = false

        testContext {
            var itemsList: List<Item> = listOf()
            reactiveScope { itemsList = cache.watch(Query())().also { println(it) } }
            launch {
                cache.regularly()
                val x = Item(1)
                cache.skipCache.insert(x)
                cache.regularly()
                assertEquals(listOf(), itemsList)

                clock += 1.seconds
                cache.regularly()
                assertEquals(listOf(), itemsList)

                cache.totallyInvalidate()
                clock += 1.seconds
                cache.regularly()
                assertEquals(listOf(x), itemsList)
            }
        }
    }

    @Test fun fetchSocketOrdering() = timeTravelTest { clock ->
        val r = MockClientModelRestEndpoints<Item, Int>(::println)
        r.holdWsMessage.permit = false
        r.holdWsConnect.permit = false
        val cache = ModelCache(r, Item.serializer())
        cache.allowLoop = false

        testContext {
            var itemsList: List<Item> = listOf()
            reactiveScope { itemsList = cache.watch(Query())().also { println(it) } }
            launch {
                val items = listOf(Item(1), Item(2), Item(3))
                r.insertBulk(items)
                clock += 1.seconds
                println("Inserted")
                cache.regularly()

                println("Everything is set up, but the socket hasn't run yet.")
                r.hold.permit = true
                cache.regularly()
                // We should NOT have fetched yet!  Until the socket is connected, we'd just have to repull anyway.  So wait.
                assertEquals(listOf(), itemsList)

                r.holdWsConnect.permit = true
                r.holdWsMessage.permit = true

                cache.regularly()
                assertEquals(items, itemsList)
            }
            launch {

            }
        }
    }
}

class CacheReadableTest {
    @Test
    fun preventsStackedPull(): Unit {
        timeTravelTest { clock ->
            var totalInvalidation = Instant.DISTANT_PAST
            val item = object : CacheReadable<Int>() {
                override val cacheTime: Duration = 1.minutes
                override val totalInvalidation: Instant get() = totalInvalidation
            }
            item.addListener {  }

            assertFalse(item.upToDate)
            assertTrue(item.shouldPull)
            assertEquals(ReadableState.notReady, item.state)
            item.onLoadStart()
            assertFalse(item.upToDate)
            assertFalse(item.shouldPull)
            assertEquals(ReadableState.notReady, item.state)
            val e = Exception("X")
            item.onRetrievalError(e)
            assertTrue(item.upToDate)
            assertFalse(item.shouldPull)
            assertEquals(e, item.state.exception)
        }
    }

    @Test
    fun fullTest(): Unit {
        timeTravelTest { clock ->
            var totalInvalidation = Instant.DISTANT_PAST
            val item = object : CacheReadable<Int>() {
                override val cacheTime: Duration = 1.minutes
                override val totalInvalidation: Instant get() = totalInvalidation
            }

            fun assertNoNeedToPullIfNotUsed() = assertFalse(item.shouldPull)
            fun assertDataAvailable() {
                assertTrue(item.upToDate)
                assertEquals(0, item.state.get())
            }

            fun assertDataNotAvailable() {
                assertFalse(item.upToDate)
                assertEquals(ReadableState.notReady, item.state)
            }

            assertDataNotAvailable()
            item.onFreshData(0)
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()
            clock += 1.seconds
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()
            clock += 1.minutes
            assertDataNotAvailable()
            assertNoNeedToPullIfNotUsed()
            item.onFreshData(0)
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()

            item.socketIsLive = true
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()
            clock += 1.seconds
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()
            clock += 1.minutes
            assertDataNotAvailable()
            assertNoNeedToPullIfNotUsed()

            item.onFreshData(0)
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()
            clock += 1.seconds
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()
            clock += 1.minutes
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()

            item.socketIsLive = false
            assertDataNotAvailable()
            assertNoNeedToPullIfNotUsed()
            clock += 1.minutes

            item.addListener { }

            assertDataNotAvailable()
            item.onFreshData(0)
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.seconds
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.minutes
            assertDataNotAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            item.onFreshData(0)
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)

            item.socketIsLive = true
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.seconds
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.minutes
            assertDataNotAvailable()
            assertEquals(!item.upToDate, item.shouldPull)

            item.onFreshData(0)
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.seconds
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.minutes
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)

            item.onFreshData(0)
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.seconds
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.seconds
            totalInvalidation = now()
            assertDataNotAvailable()
            assertEquals(!item.upToDate, item.shouldPull)

            item.socketIsLive = false
            assertDataNotAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
        }
    }

    @Test
    fun fullTestNoLoad(): Unit {
        timeTravelTest { clock ->
            var totalInvalidation = Instant.DISTANT_PAST
            val item = object : CacheReadable<Int>() {
                override val cacheTime: Duration = 1.minutes
                override val totalInvalidation: Instant get() = totalInvalidation
                override val showReload: Boolean get() = false
            }

            fun assertNoNeedToPullIfNotUsed() = assertFalse(item.shouldPull)
            fun assertDataAvailable() {
                assertTrue(item.upToDate)
                assertEquals(0, item.state.get())
            }

            fun assertDataNotAvailable() {
                assertFalse(item.upToDate)
            }

            assertDataNotAvailable()
            item.onFreshData(0)
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()
            clock += 1.seconds
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()
            clock += 1.minutes
            assertDataNotAvailable()
            assertEquals(ReadableState(0), item.state)
            assertNoNeedToPullIfNotUsed()
            item.onFreshData(0)
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()

            item.socketIsLive = true
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()
            clock += 1.seconds
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()
            clock += 1.minutes
            assertDataNotAvailable()
            assertNoNeedToPullIfNotUsed()

            item.onFreshData(0)
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()
            clock += 1.seconds
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()
            clock += 1.minutes
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()

            item.socketIsLive = false
            assertDataNotAvailable()
            assertNoNeedToPullIfNotUsed()
            clock += 1.minutes

            item.addListener { }

            assertDataNotAvailable()
            item.onFreshData(0)
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.seconds
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.minutes
            assertDataNotAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            item.onFreshData(0)
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)

            item.socketIsLive = true
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.seconds
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.minutes
            assertDataNotAvailable()
            assertEquals(!item.upToDate, item.shouldPull)

            item.onFreshData(0)
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.seconds
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.minutes
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)

            item.onFreshData(0)
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.seconds
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.seconds
            totalInvalidation = now()
            assertDataNotAvailable()
            assertEquals(ReadableState.notReady, item.state)
            assertEquals(!item.upToDate, item.shouldPull)

            item.socketIsLive = false
            assertDataNotAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
        }
    }

    @Test
    fun fullTestNoLoad2(): Unit {
        timeTravelTest { clock ->
            var totalInvalidation = Instant.DISTANT_PAST
            val item = object : CacheReadable<Int>() {
                override val cacheTime: Duration = 1.minutes
                override val totalInvalidation: Instant get() = totalInvalidation
                override val showReload: Boolean get() = false
                override val showReloadOnInvalidate: Boolean get() = false
            }

            fun assertNoNeedToPullIfNotUsed() = assertFalse(item.shouldPull)
            fun assertDataAvailable() {
                assertTrue(item.upToDate)
                assertEquals(0, item.state.get())
            }

            fun assertDataNotAvailable() {
                assertFalse(item.upToDate)
            }

            assertDataNotAvailable()
            item.onFreshData(0)
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()
            clock += 1.seconds
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()
            clock += 1.minutes
            assertDataNotAvailable()
            assertEquals(ReadableState(0), item.state)
            assertNoNeedToPullIfNotUsed()
            item.onFreshData(0)
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()

            item.socketIsLive = true
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()
            clock += 1.seconds
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()
            clock += 1.minutes
            assertDataNotAvailable()
            assertNoNeedToPullIfNotUsed()

            item.onFreshData(0)
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()
            clock += 1.seconds
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()
            clock += 1.minutes
            assertDataAvailable()
            assertNoNeedToPullIfNotUsed()

            item.socketIsLive = false
            assertDataNotAvailable()
            assertNoNeedToPullIfNotUsed()
            clock += 1.minutes

            item.addListener { }

            assertDataNotAvailable()
            item.onFreshData(0)
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.seconds
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.minutes
            assertDataNotAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            item.onFreshData(0)
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)

            item.socketIsLive = true
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.seconds
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.minutes
            assertDataNotAvailable()
            assertEquals(!item.upToDate, item.shouldPull)

            item.onFreshData(0)
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.seconds
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.minutes
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)

            item.onFreshData(0)
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.seconds
            assertDataAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
            clock += 1.seconds
            totalInvalidation = now()
            assertDataNotAvailable()
            assertEquals(ReadableState(0), item.state)
            assertEquals(!item.upToDate, item.shouldPull)

            item.socketIsLive = false
            assertDataNotAvailable()
            assertEquals(!item.upToDate, item.shouldPull)
        }
    }

}

class UpdatingQueryListTest {
    companion object {
        init {
            prepareModels()
        }
        var created: Int = 0
        fun item(index: Int) = Item(index, created++)
    }

    class TestWrapper<T : HasId<ID>, ID : Comparable<ID>>(val query: Query<T>, val asserting: Boolean = true) {
        val underlying: UpdatingQueryList<T, ID> = UpdatingQueryList<T, ID>(query)
        var list: List<T> = listOf()
        val comparator: Comparator<T> get() = underlying.comparator
        var complete = false

        fun queueItemUpdate(item: T) {
            underlying.queueItemUpdate(item)
            val afterEnd = list.lastOrNull()?.let { comparator.compare(item, it) > 0 } ?: false
            val oldList = list
            if(!afterEnd || complete) {
                list = listOf(item).plus(list).distinctBy { it._id }.filter { query.condition(it) }.sortedWith(comparator)
            } else {
                list = list.filter { it._id != item._id }
            }
            if(asserting) {
                assertEquals(list, underlying.queued)
                assertEquals(list != oldList, underlying.updatesMade)
            }
            underlying.updatesMade = false
        }

        fun fullPull(item: List<T>) {
            val correctedList = item.filter { query.condition(it) }.sortedWith(comparator).take(query.limit)
            underlying.fullPull(correctedList)
            list = correctedList
            complete = list.size < query.limit
            if(asserting) assertEquals(list, underlying.queued)
            underlying.updatesMade = false
        }

        fun delete(id: ID) {
            underlying.delete(id)
            val oldList = list
            list = list.filter { it._id != id }
            if(asserting) {
                assertEquals(list, underlying.queued)
                assertEquals(list != oldList, underlying.updatesMade)
            }
            underlying.updatesMade = false
        }
    }

    @Test fun insertsFromEmpty() {
        val items = TestWrapper<Item, Int>(Query(), asserting = true)
        items.fullPull(listOf())
        println(items.list)
        items.queueItemUpdate(Item(1))
        println(items.list)
        items.queueItemUpdate(Item(2))
        println(items.list)
    }

    /**
     * The last item stays because it's less than what we used to have.
     */
    @Test fun replaceEndLt() {
        val items = TestWrapper<Item, Int>(Query(limit = 4, orderBy = sort { it.creation.ascending() }))
        items.fullPull((0..20 step 2).map { item(it); item(it) })
        assertEquals(4, items.list.size)
        items.queueItemUpdate(items.list.last().copy(creation = items.list.last().creation - 1))
        assertEquals(4, items.list.size)
    }

    /**
     * The last item disappears because it's greater than what we used to have.  No way to guarantee if there's something between.
     */
    @Test fun replaceEndGte() {
        val items = TestWrapper<Item, Int>(Query(limit = 4, orderBy = sort { it.creation.ascending() }))
        items.fullPull((0..20 step 2).map { item(it); item(it) })
        assertEquals(4, items.list.size)
        items.queueItemUpdate(items.list.last().copy(creation = items.list.last().creation + 1))
        assertEquals(3, items.list.size)
    }

    @Test fun targeted() {
        val items = TestWrapper<Item, Int>(Query(limit = 4, orderBy = sort { it._id.ascending() }))
        println(items.query)
        items.fullPull((0..20 step 2).map { item(it) })
        println(items.list)
        items.queueItemUpdate(item(6))
        println(items.list)
    }

    @Test fun weirdUpdateCase() {
        val items = TestWrapper<Item, Int>(Query(limit = 4, orderBy = sort { it.creation.ascending() }), asserting = false)
        println(items.query)
        items.fullPull((0..20 step 2).map { item(it) })
        println(items.list)
        items.queueItemUpdate(item(5))
        println(items.list)
    }

    fun testDatas() = listOf(
        TestWrapper<Item, Int>(Query()),
        TestWrapper<Item, Int>(Query(condition = condition { it._id lt 10 })),
        TestWrapper<Item, Int>(Query(condition = condition { it._id lt 10 }, orderBy = sort { it._id.descending() })),
        TestWrapper<Item, Int>(Query(condition = condition { it._id lt 10 }, orderBy = sort { it.creation.descending() })),
        TestWrapper<Item, Int>(Query(orderBy = sort { it._id.descending() })),
        TestWrapper<Item, Int>(Query(orderBy = sort { it.creation.descending() })),
        TestWrapper<Item, Int>(Query(condition = condition { it._id lt 10 }, orderBy = sort { it._id.ascending() })),
        TestWrapper<Item, Int>(Query(condition = condition { it._id lt 10 }, orderBy = sort { it.creation.ascending() })),
        TestWrapper<Item, Int>(Query(orderBy = sort { it._id.ascending() })),
        TestWrapper<Item, Int>(Query(orderBy = sort { it.creation.ascending() })),
        TestWrapper<Item, Int>(Query(limit = 4, orderBy = sort { it._id.ascending() })),
        TestWrapper<Item, Int>(Query(limit = 4, orderBy = sort { it.creation.ascending() })),
        TestWrapper<Item, Int>(Query(limit = 4000, orderBy = sort { it._id.ascending() })),
        TestWrapper<Item, Int>(Query(limit = 4000, orderBy = sort { it.creation.ascending() })),
    )

    @Test
    fun insertOK() {
        testDatas().forEach { items ->
            println(items.query)
            items.fullPull((0..20 step 2).map { item(it) })
            items.queueItemUpdate(item(5))
        }
    }
    @Test
    fun replaceOK() {
        testDatas().forEach { items ->
            println(items.query)
            items.fullPull((0..20 step 2).map { item(it) })
            items.queueItemUpdate(item(6))
        }
    }
    @Test fun deleteOk() {
        testDatas().forEach { items ->
            println(items.query)
            items.fullPull((0..20 step 2).map { item(it) })
            items.delete(6)
        }
    }

    @Test fun fromScratch() {
        testDatas().forEach { items ->
            items.fullPull(listOf())
            repeat(10) {
                items.queueItemUpdate(item(it))
            }
            println(items.list)
        }
    }
}

fun <T> List<T>.isSorted(comparable: Comparator<T>): Boolean {
    var last: T? = null
    return this.all {
        val r = last == null || comparable.compare(it, last as T) > 0
        last = it
        r
    }
}

class WatchingWrapperTests {
    @Test fun test() {
        var state = 0
        testContext {
            val delay = VirtualDelayer()
            val resource = object : OutsideResource {
                override suspend fun start(): Boolean {
                    state++
                    delay.await()
                    state++
                    return true
                }

                override fun stop() {
                    state++
                }
            }
            val cache = object : CacheReadable<Item>() {
                override val cacheTime: Duration = 1.minutes
                override val totalInvalidation: Instant = Instant.DISTANT_PAST
            }
            val wrapper = WatchingWrapper(cache, resource)

            assertEquals(0, state)
            reactiveScope {
                println("Got: ${wrapper.await()}")
            }
            assertEquals(1, state)
            delay.go()
            assertEquals(2, state)
            cache.onFreshData(Item(1))
        }.cancel()
        assertEquals(3, state)
    }

    @Test fun multistartRunsOnce() {
        var state = 0
        testContext {
            val delay = VirtualDelayer()
            val resource = object : OutsideResource {
                override suspend fun start(): Boolean {
                    state++
                    delay.await()
                    state++
                    return true
                }

                override fun stop() {
                    state++
                }
            }
            val cache = object : CacheReadable<Item>() {
                override val cacheTime: Duration = 1.minutes
                override val totalInvalidation: Instant = Instant.DISTANT_PAST
            }
            val wrapper = WatchingWrapper(cache, resource)

            assertEquals(0, state)
            reactiveScope { println("Got: ${wrapper.await()}") }
            reactiveScope { println("Got: ${wrapper.await()}") }
            assertEquals(1, state)
            delay.go()
            assertEquals(2, state)
            cache.onFreshData(Item(1))
        }.cancel()
        assertEquals(3, state)
    }

    @Test fun interrupt() {
        var state = 0
        testContext {
            val delay = VirtualDelayer()
            val resource = object : OutsideResource {
                override suspend fun start(): Boolean {
                    state++
                    delay.await()
                    state++
                    return true
                }

                override fun interruptStartup() {
                    state++
                }

                override fun stop() {
                }
            }
            val cache = object : CacheReadable<Item>() {
                override val cacheTime: Duration = 1.minutes
                override val totalInvalidation: Instant = Instant.DISTANT_PAST
            }
            val wrapper = WatchingWrapper(cache, resource)

            assertEquals(0, state)
            reactiveScope { println("Got: ${wrapper.await()}") }
            assertEquals(1, state)
            cache.onFreshData(Item(1))
        }.cancel()
        assertEquals(2, state)
    }
}

class ChangeUpdateWrapperTest {
    @Test fun test() {
        var c: Condition<Item> = condition(false)
        val onMessage = ArrayList<(CollectionUpdates<Item, Int>)->Unit>()
        fun serverSends(value: CollectionUpdates<Item, Int>) = onMessage.forEach { it(value) }
        val artificialSocket = object: TypedWebSocket<Condition<Item>, CollectionUpdates<Item, Int>> {
            override val connected = Property(false).also {
                it.addListener { println("connected: ${it.value}") }
            }

            override fun close(code: Short, reason: String) = TODO()

            override fun onClose(action: (Short) -> Unit) {

            }

            val onOpen = ArrayList<()->Unit>()
            override fun onOpen(action: () -> Unit) {
                onOpen.add(action)
            }

            override fun send(data: Condition<Item>) {
                c = data
                // return ack
                onMessage.forEach { it(CollectionUpdates(condition = data)) }
            }

            override fun onMessage(action: (CollectionUpdates<Item, Int>) -> Unit) {
                onMessage.add(action)
            }

            var uses = 0
            override fun start(): () -> Unit {
                println("Start")
                if(uses++ == 0) {
                    connected.value = true
                    onOpen.invokeAllSafe()
                }
                return {
                    if(--uses == 0) {
                        connected.value = false
                        println("end")
                    }
                }
            }
        }
        val x = ChangeUpdateWrapper(artificialSocket) { println(it) }
        launchGlobal {
            x.update(Condition.Always())
            assertEquals(true, artificialSocket.connected.value)
            x.update(condition { it._id eq 3 })
            assertEquals(true, artificialSocket.connected.value)
            serverSends(CollectionUpdates(updates = setOf(Item(1))))
            x.update(condition(false))
            assertEquals(false, artificialSocket.connected.value)
            println("we're gonna keep going, right?")
            x.update(condition(true))
            assertEquals(true, artificialSocket.connected.value)
        }
    }
}

class SharedChangeUpdateWrapperTest {
    @Test fun test() {
        var c: Condition<Item> = condition(false)
        val onMessage = ArrayList<(CollectionUpdates<Item, Int>)->Unit>()
        fun serverSends(value: CollectionUpdates<Item, Int>) = onMessage.forEach { it(value) }
        val artificialSocket = object: TypedWebSocket<Condition<Item>, CollectionUpdates<Item, Int>> {
            override val connected = Property(false).also {
                it.addListener { println("connected: ${it.value}") }
            }

            override fun close(code: Short, reason: String) = TODO()

            override fun onClose(action: (Short) -> Unit) {

            }

            val onOpen = ArrayList<()->Unit>()
            override fun onOpen(action: () -> Unit) {
                onOpen.add(action)
            }

            override fun send(data: Condition<Item>) {
                c = data
                // return ack
                onMessage.forEach { it(CollectionUpdates(condition = data)) }
            }

            override fun onMessage(action: (CollectionUpdates<Item, Int>) -> Unit) {
                onMessage.add(action)
            }

            var uses = 0
            override fun start(): () -> Unit {
                if(uses++ == 0) {
                    connected.value = true
                    onOpen.invokeAllSafe()
                }
                return { if(--uses == 0) connected.value = false }
            }
        }
        val x = SharedChangeUpdateWrapper(artificialSocket) { println(it) }
        testContext {
            launch {
                x.outsideResource(condition { it._id.lt(5) }).start()
                println("CONNECTED!")
                x.outsideResource(condition { it._id.lt(5) }).stop()
                println("closing.")
            }
            launch {
                println(x.queuedCondition)
                x.flush()
            }
        }
        launchGlobal {
//            x.update(Condition.Always())
//            assertEquals(true, artificialSocket.connected.value)
//            x.update(condition { it._id eq 3 })
//            assertEquals(true, artificialSocket.connected.value)
//            serverSends(CollectionUpdates(updates = setOf(Item(1))))
//            x.update(condition(false))
//            assertEquals(false, artificialSocket.connected.value)
        }
    }
}
