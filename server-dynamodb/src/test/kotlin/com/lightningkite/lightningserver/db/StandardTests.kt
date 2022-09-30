package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.test.*
import com.lightningkite.lightningserver.cache.CacheInterface
import com.lightningkite.lightningserver.cache.CacheTest
import com.lightningkite.lightningserver.db.InMemoryDatabase
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import kotlin.test.assertEquals

object DynamoForTests {
    var d = DynamoDbCache(embeddedDynamo())

}

class DynamoTest : CacheTest() {
    override val cache: CacheInterface?
        get() = DynamoForTests.d

    @Test fun parsing() {
        val target = setOf("asdf", "fdsa")
        val serializer = SetSerializer(String.serializer())
        assertEquals(target, serializer.fromDynamo(serializer.toDynamo(target)))
    }
}
