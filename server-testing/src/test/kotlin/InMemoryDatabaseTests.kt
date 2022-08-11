package com.lightningkite.lightningdb.test

import com.lightningkite.lightningdb.Database
import com.lightningkite.lightningserver.cache.CacheInterface
import com.lightningkite.lightningserver.cache.CacheTest
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.db.InMemoryDatabase

class RamAggregationsTest: AggregationsTest() {
    override val database: Database = InMemoryDatabase()
}
class RamConditionTests: ConditionTests() {
    override val database: Database = InMemoryDatabase()
}
class RamModificationTests: ModificationTests() {
    override val database: Database = InMemoryDatabase()
}
class RamSortTest: SortTest() {
    override val database: Database = InMemoryDatabase()
}
class RamMetaTest: MetaTest() {
    override val database: Database = InMemoryDatabase()
}

class LocalCacheTest: CacheTest() {
    override val cache: CacheInterface = LocalCache
}