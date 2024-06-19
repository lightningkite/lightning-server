package com.lightningkite.lightningserver.db

import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.CacheTest
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.test.assertEquals

object DynamoForTests {
    var d = DynamoDbCache( { embeddedDynamo() })

}

class DynamoTest : CacheTest() {
    override val cache: Cache?
        get() = DynamoForTests.d

    @Test fun parsing() {
        val target = setOf("asdf", "fdsa")
        val serializer = SetSerializer(String.serializer())
        assertEquals(target, serializer.fromDynamo(serializer.toDynamo(target)))
    }
}
