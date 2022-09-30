package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.test.*
import com.lightningkite.lightningserver.cache.CacheInterface
import com.lightningkite.lightningserver.cache.CacheTest
import com.lightningkite.lightningserver.db.InMemoryDatabase
import org.junit.AfterClass
import org.junit.BeforeClass
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

object DynamoForTests {
    var d = DynamoDbCache(embeddedDynamo())

}

class DynamoTest : CacheTest() {
    override val cache: CacheInterface?
        get() = DynamoForTests.d
}
