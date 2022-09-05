package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.test.*
import com.lightningkite.lightningserver.db.InMemoryDatabase
import org.junit.AfterClass
import org.junit.BeforeClass
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

object DynamoForTests {
    var d = DynamoDatabase(embeddedDynamo())

}

class DynamoAggregationsTest : AggregationsTest() {
    override val database = DynamoForTests.d
}

class DynamoConditionTests : ConditionTests() {
    override val database = DynamoForTests.d
}

class DynamoModificationTests : ModificationTests() {
    override val database = DynamoForTests.d
}

class DynamoSortTest : SortTest() {
    override val database = DynamoForTests.d
}

class DynamoMetaTest : MetaTest() {
    override val database = DynamoForTests.d
}